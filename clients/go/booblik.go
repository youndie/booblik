// Package booblik is a client for booblik, a message broker on an append-only log.
//
// It speaks PRODUCE, FETCH and METADATA — publishing is in this file and in producer.go, reading in
// consumer.go. The two halves are not the same size: a publisher that gets something wrong is told
// so by the broker, while a consumer that gets something wrong returns plausible bytes or stops
// quietly, so the reading half carries the checksum, the truncated tail and the position.
//
// The protocol is docs/api/protocol-wire.md; where this disagrees with it, this is wrong.
package booblik

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync/atomic"
	"time"
)

const (
	apiProduce  int16 = 1
	apiFetch    int16 = 2
	apiMetadata int16 = 3

	versionV1 int16 = 1
	// versionV2 exists for FETCH alone, where it adds maxWaitMillis and minBytes. PRODUCE and
	// METADATA have no v2.
	versionV2 int16 = 2

	// What the length prefix counts before the payload: apiKey, apiVersion, correlationId.
	requestHeaderBytes = 2 + 2 + 4
	// correlationId and errorCode, which every response carries.
	responseHeaderBytes = 4 + 2
	// payloadSize and crc32c, in front of every record inside a FETCH response — the on-disk format
	// unchanged, which is what lets the broker send segment bytes without touching them.
	recordHeaderBytes = 4 + 4

	maxFrameBytes = 8 << 20
)

// AckPolicy is how long the broker waits before answering.
type AckPolicy int8

const (
	// AckNone does not answer at all. Not an empty response — nothing: no offset exists until the
	// writer reaches the batch, so there would be nothing to put in one. It is also the only mode
	// in which the broker may lose an accepted record, and the client will not hear about that or
	// about the broker being overloaded.
	AckNone AckPolicy = 0
	// AckWritten answers once the record is in the log, before any durability barrier.
	AckWritten AckPolicy = 1
	// AckForced answers after force(). Not one barrier per request: the broker groups everyone
	// already queued into one.
	AckForced AckPolicy = 2
)

// PartitionInfo is what METADATA says about one partition.
type PartitionInfo struct {
	Partition int32
	// LogStartOffset is where the *live* log begins, after retention. Reading "from the start"
	// means starting here and not at zero — zero is OFFSET_OUT_OF_RANGE on any topic that has ever
	// dropped a segment.
	LogStartOffset int64
	// HighWatermark is the first offset that does not exist yet.
	HighWatermark int64
}

// ProduceResult is the offsets a batch was given. The records are contiguous from BaseOffset.
type ProduceResult struct {
	BaseOffset   int64
	LogEndOffset int64
}

// Conn is one connection to a broker. It is not safe for concurrent use: requests and responses are
// matched by a correlation id in the order they were sent, so two goroutines sharing a Conn would
// read each other's answers. Use one Conn per goroutine, or a Producer, which owns its own.
type Conn struct {
	conn        net.Conn
	correlation int32
}

// Dial opens a connection. The context bounds the dial only; per-request deadlines come from the
// context passed to each method.
func Dial(ctx context.Context, address string) (*Conn, error) {
	var dialer net.Dialer
	conn, err := dialer.DialContext(ctx, "tcp", address)
	if err != nil {
		return nil, fmt.Errorf("booblik: dial %s: %w", address, err)
	}
	// TCP_NODELAY is Go's default for TCP connections, and it is the one that matters here: the
	// requests are small and a delayed acknowledgement would be added to every round trip.
	return &Conn{conn: conn}, nil
}

func (c *Conn) Close() error { return c.conn.Close() }

// withContext applies ctx to the connection for one exchange and returns the undo.
//
// Moving the deadline into the past is the only way to interrupt a blocking socket operation in Go,
// so cancellation is implemented by doing exactly that. Without this, a context with no deadline
// would be honoured only between calls, which is not what a caller passing one means.
func (c *Conn) withContext(ctx context.Context) func() {
	if deadline, ok := ctx.Deadline(); ok {
		_ = c.conn.SetDeadline(deadline)
	}
	if ctx.Done() == nil {
		return func() { _ = c.conn.SetDeadline(time.Time{}) }
	}
	done := make(chan struct{})
	go func() {
		select {
		case <-ctx.Done():
			_ = c.conn.SetDeadline(time.Now())
		case <-done:
		}
	}()
	return func() {
		close(done)
		_ = c.conn.SetDeadline(time.Time{})
	}
}

func (c *Conn) send(apiKey, version int16, payload []byte) (int32, error) {
	c.correlation++
	frameLength := requestHeaderBytes + len(payload)

	frame := make([]byte, 4+frameLength)
	binary.BigEndian.PutUint32(frame[0:], uint32(frameLength))
	binary.BigEndian.PutUint16(frame[4:], uint16(apiKey))
	binary.BigEndian.PutUint16(frame[6:], uint16(version))
	binary.BigEndian.PutUint32(frame[8:], uint32(c.correlation))
	copy(frame[12:], payload)

	if _, err := c.conn.Write(frame); err != nil {
		return 0, fmt.Errorf("booblik: write request: %w", err)
	}
	return c.correlation, nil
}

func (c *Conn) receive(expect int32) ([]byte, error) {
	var prefix [4]byte
	if _, err := io.ReadFull(c.conn, prefix[:]); err != nil {
		return nil, fmt.Errorf("booblik: read response length: %w", err)
	}
	length := int(binary.BigEndian.Uint32(prefix[:]))
	if length < responseHeaderBytes || length > maxFrameBytes {
		return nil, fmt.Errorf("booblik: response frame length %d is out of range", length)
	}

	frame := make([]byte, length)
	if _, err := io.ReadFull(c.conn, frame); err != nil {
		return nil, fmt.Errorf("booblik: read response: %w", err)
	}

	correlation := int32(binary.BigEndian.Uint32(frame[0:]))
	code := Code(binary.BigEndian.Uint16(frame[4:]))

	// Checked rather than trusted. A response carrying somebody else's correlation id does not
	// merely lose information — it **resolves the wrong request**, handing one caller another
	// caller's offsets.
	if correlation != expect {
		return nil, fmt.Errorf("booblik: response %d answered request %d", correlation, expect)
	}
	if code != CodeNone {
		return nil, &BrokerError{Code: code}
	}
	return frame[responseHeaderBytes:], nil
}

// Metadata asks which topics exist and where each partition currently starts and ends. No topics
// means all of them.
//
// A named topic that does not exist fails the whole request with UNKNOWN_TOPIC_OR_PARTITION rather
// than being left out of the answer — otherwise "no such topic" and "the topic is empty" would
// arrive looking identical.
func (c *Conn) Metadata(ctx context.Context, topics ...string) (map[string][]PartitionInfo, error) {
	defer c.withContext(ctx)()

	payload := make([]byte, 4, 4+len(topics)*16)
	binary.BigEndian.PutUint32(payload, uint32(len(topics)))
	for _, topic := range topics {
		payload = binary.BigEndian.AppendUint16(payload, uint16(len(topic)))
		payload = append(payload, topic...)
	}

	correlation, err := c.send(apiMetadata, versionV1, payload)
	if err != nil {
		return nil, err
	}
	body, err := c.receive(correlation)
	if err != nil {
		return nil, err
	}
	return decodeMetadata(body)
}

func decodeMetadata(body []byte) (map[string][]PartitionInfo, error) {
	r := &reader{buf: body}
	count := r.int32()
	result := make(map[string][]PartitionInfo, count)

	for i := int32(0); i < count; i++ {
		name := r.str()
		partitions := make([]PartitionInfo, 0, r.int32())
		for j := 0; j < cap(partitions); j++ {
			partitions = append(partitions, PartitionInfo{
				Partition:      r.int32(),
				LogStartOffset: r.int64(),
				HighWatermark:  r.int64(),
			})
		}
		result[name] = partitions
	}
	if r.err != nil {
		return nil, fmt.Errorf("booblik: decode METADATA: %w", r.err)
	}
	return result, nil
}

// Produce appends records to one partition as a single request.
//
// The records land contiguously — one request is written by one call into that partition's writer,
// so the offsets are BaseOffset, BaseOffset+1 and so on with nothing interleaved. It is **not**
// atomic: a crash mid-write leaves whatever reached the disk, and recovery keeps the prefix that
// passes its checksums. There are no transactions in this broker.
//
// Returns nil with AckNone, because no answer is coming.
//
// Nothing here validates record sizes. The broker refuses empty records and empty batches with
// CORRUPT_REQUEST, and duplicating that rule client-side would create a second place to disagree
// with it — the rule belongs to whatever has to store the result.
func (c *Conn) Produce(
	ctx context.Context,
	topic string,
	partition int32,
	records [][]byte,
	ack AckPolicy,
) (*ProduceResult, error) {
	defer c.withContext(ctx)()

	size := 2 + len(topic) + 4 + 1 + 4
	for _, record := range records {
		size += 4 + len(record)
	}

	payload := make([]byte, 0, size)
	payload = binary.BigEndian.AppendUint16(payload, uint16(len(topic)))
	payload = append(payload, topic...)
	payload = binary.BigEndian.AppendUint32(payload, uint32(partition))
	payload = append(payload, byte(ack))
	payload = binary.BigEndian.AppendUint32(payload, uint32(len(records)))
	for _, record := range records {
		payload = binary.BigEndian.AppendUint32(payload, uint32(len(record)))
		payload = append(payload, record...)
	}

	correlation, err := c.send(apiProduce, versionV1, payload)
	if err != nil {
		return nil, err
	}
	if ack == AckNone {
		return nil, nil
	}

	body, err := c.receive(correlation)
	if err != nil {
		return nil, err
	}
	if len(body) < 16 {
		return nil, fmt.Errorf("booblik: PRODUCE response is %d bytes, expected 16", len(body))
	}
	return &ProduceResult{
		BaseOffset:   int64(binary.BigEndian.Uint64(body[0:])),
		LogEndOffset: int64(binary.BigEndian.Uint64(body[8:])),
	}, nil
}

// Topic is one topic with its partitions, so the name and the routing stop being arguments to every
// call. Obtain it with Conn.Topic.
type Topic struct {
	conn       *Conn
	Name       string
	Partitions []int32

	roundRobin atomic.Uint32
}

// Topic asks the broker which partitions the topic has.
//
// Asking is the point. A partition count supplied by hand can disagree with the broker, and what
// that produces — records piling into the partitions that exist while others are never written to —
// reads as a data problem rather than as the configuration mistake it is.
func (c *Conn) Topic(ctx context.Context, name string) (*Topic, error) {
	answer, err := c.Metadata(ctx, name)
	if err != nil {
		return nil, err
	}
	infos, ok := answer[name]
	if !ok || len(infos) == 0 {
		return nil, fmt.Errorf("booblik: broker has no partitions for %q", name)
	}

	partitions := make([]int32, len(infos))
	for i, info := range infos {
		partitions[i] = info.Partition
	}
	return &Topic{conn: c, Name: name, Partitions: partitions}, nil
}

// PartitionFor is where a record with this key goes.
//
// A nil key takes the next partition round-robin, and that counter advances on every call — so
// asking and then sending is two turns of it and the records start skipping partitions. With a key
// there is no such thing, the answer being a pure function of the key. Round-robin rather than
// random because a handful of unkeyed records should look evenly spread, and random visibly clumps
// at small counts in a way that reads as a bug.
func (t *Topic) PartitionFor(key []byte) int32 {
	if key == nil {
		return t.Partitions[int(t.roundRobin.Add(1)-1)%len(t.Partitions)]
	}
	return t.Partitions[PartitionFor(key, len(t.Partitions))]
}

// Send publishes one record, choosing its partition from key.
//
// One record per request throws away the largest single performance factor there is: the broker's
// own measurements put batches of a hundred at 4.3M records/s against 80k one at a time. Use
// Conn.Produce with a slice, or a Producer, whenever records are available together.
func (t *Topic) Send(ctx context.Context, key, record []byte, ack AckPolicy) (*ProduceResult, error) {
	return t.conn.Produce(ctx, t.Name, t.PartitionFor(key), [][]byte{record}, ack)
}

// reader is a cursor that records the first failure instead of panicking on a short buffer, so a
// truncated response is one error at the end rather than a check after every field.
type reader struct {
	buf []byte
	pos int
	err error
}

func (r *reader) take(n int) []byte {
	if r.err != nil {
		return make([]byte, n)
	}
	if r.pos+n > len(r.buf) {
		r.err = fmt.Errorf("response ends after %d bytes, needed %d more", len(r.buf), r.pos+n-len(r.buf))
		return make([]byte, n)
	}
	out := r.buf[r.pos : r.pos+n]
	r.pos += n
	return out
}

func (r *reader) int32() int32 { return int32(binary.BigEndian.Uint32(r.take(4))) }
func (r *reader) int64() int64 { return int64(binary.BigEndian.Uint64(r.take(8))) }
func (r *reader) str() string  { return string(r.take(int(binary.BigEndian.Uint16(r.take(2))))) }
