package booblik

import (
	"encoding/binary"
	"fmt"
	"hash/crc32"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
)

// fakeBroker is a broker for tests: it speaks the protocol well enough to answer, and it **decodes
// what the client encoded** rather than pattern-matching bytes. That is the point of it — an
// encoding mistake becomes a decode failure here instead of a mystery against a real broker, and
// `go test` needs no Docker, no network and no fixtures.
//
// It is deliberately a separate reading of docs/api/protocol-wire.md from the client beside it.
type fakeBroker struct {
	listener net.Listener

	mu         sync.Mutex
	produced   map[batchKey][][]byte
	requests   int
	nextOffset int64

	// Atomic and not a plain field: the test that checks a refusal changes it while the serving
	// goroutine is reading it, which `go test -race` is right to object to.
	refuseWith atomic.Int32
	// Partitions reported by METADATA. Fixed at construction rather than assigned afterwards, for
	// the same reason — a broker whose shape can change under its own goroutine is a racy fixture,
	// and a racy fixture produces failures that read as bugs in the code under test.
	partitions int32

	// corrupt flips a bit in every record's stored checksum, which is what a damaged segment looks
	// like from the socket: the bytes arrive, and only the sum disagrees with them.
	corrupt atomic.Bool
	// lastVersion is the apiVersion of the last request, so a test can assert which one the client
	// actually put on the wire rather than which one it meant to.
	lastVersion atomic.Int32
	lastFetch   atomic.Pointer[fetchRequest]
}

// fetchRequest is the fake broker's reading of a FETCH frame — a second decoding of
// docs/api/protocol-wire.md §4, so an encoding mistake fails here instead of arriving as an
// inexplicable answer.
type fetchRequest struct {
	topic     string
	partition int32
	offset    int64
	maxBytes  int32
	maxWait   int32
	minBytes  int32
}

func startFakeBroker(t *testing.T, partitions int32) *fakeBroker {
	t.Helper()

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}

	broker := &fakeBroker{
		listener:   listener,
		produced:   make(map[batchKey][][]byte),
		partitions: partitions,
	}
	go broker.accept()
	t.Cleanup(func() { _ = listener.Close() })
	return broker
}

func (b *fakeBroker) refuse(code Code) { b.refuseWith.Store(int32(code)) }

func (b *fakeBroker) refusal() Code { return Code(b.refuseWith.Load()) }

func (b *fakeBroker) address() string { return b.listener.Addr().String() }

func (b *fakeBroker) accept() {
	for {
		conn, err := b.listener.Accept()
		if err != nil {
			return
		}
		go b.serve(conn)
	}
}

func (b *fakeBroker) recordsIn(topic string, partition int32) [][]byte {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.produced[batchKey{topic, partition}]
}

func (b *fakeBroker) requestCount() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.requests
}

func (b *fakeBroker) serve(conn net.Conn) {
	defer func() { _ = conn.Close() }()

	for {
		var prefix [4]byte
		if _, err := io.ReadFull(conn, prefix[:]); err != nil {
			return
		}
		frame := make([]byte, binary.BigEndian.Uint32(prefix[:]))
		if _, err := io.ReadFull(conn, frame); err != nil {
			return
		}

		apiKey := int16(binary.BigEndian.Uint16(frame[0:]))
		apiVersion := int16(binary.BigEndian.Uint16(frame[2:]))
		correlation := int32(binary.BigEndian.Uint32(frame[4:]))
		payload := frame[8:]
		b.lastVersion.Store(int32(apiVersion))

		var body []byte
		var silent bool
		switch apiKey {
		case apiProduce:
			body, silent = b.handleProduce(payload)
		case apiFetch:
			body = b.handleFetch(payload)
		case apiMetadata:
			body = b.handleMetadata(payload)
		default:
			body = nil
		}
		if silent {
			continue
		}
		if _, err := conn.Write(b.respond(correlation, body)); err != nil {
			return
		}
	}
}

func (b *fakeBroker) respond(correlation int32, body []byte) []byte {
	frame := make([]byte, 4+responseHeaderBytes+len(body))
	binary.BigEndian.PutUint32(frame[0:], uint32(responseHeaderBytes+len(body)))
	binary.BigEndian.PutUint32(frame[4:], uint32(correlation))
	binary.BigEndian.PutUint16(frame[8:], uint16(b.refusal()))
	copy(frame[10:], body)
	return frame
}

// handleProduce returns the response body, and whether to stay silent — which is what AckNone means
// on the wire and is the behaviour a client most often gets wrong.
func (b *fakeBroker) handleProduce(payload []byte) ([]byte, bool) {
	r := &reader{buf: payload}
	topic := r.str()
	partition := r.int32()
	ack := AckPolicy(r.take(1)[0])
	count := r.int32()

	records := make([][]byte, 0, count)
	for i := int32(0); i < count; i++ {
		records = append(records, append([]byte(nil), r.take(int(r.int32()))...))
	}
	if r.err != nil {
		panic(fmt.Sprintf("fake broker could not decode PRODUCE: %v", r.err))
	}

	b.mu.Lock()
	base := b.nextOffset
	if b.refusal() == CodeNone {
		key := batchKey{topic, partition}
		b.produced[key] = append(b.produced[key], records...)
		b.nextOffset += int64(len(records))
	}
	b.requests++
	b.mu.Unlock()

	if ack == AckNone {
		return nil, true
	}
	body := make([]byte, 16)
	binary.BigEndian.PutUint64(body[0:], uint64(base))
	binary.BigEndian.PutUint64(body[8:], uint64(base+int64(len(records))))
	return body, false
}

// seed appends records to a partition's log without going through PRODUCE, so a fetch test states
// what is there to read instead of arranging for it.
//
// Offsets in this fixture are indices into that slice, per partition. (The produce path keeps one
// counter for the whole broker, which is enough for what its own tests assert and is left alone.)
func (b *fakeBroker) seed(topic string, partition int32, records ...[]byte) {
	b.mu.Lock()
	defer b.mu.Unlock()
	key := batchKey{topic, partition}
	b.produced[key] = append(b.produced[key], records...)
}

// handleFetch answers from the seeded log, framing records exactly as the disk holds them —
// payloadSize, crc32c, payload — and cutting the response at maxBytes **in bytes**, which is what
// puts a partial record at the end of a full response.
//
// maxWait is decoded and recorded, and then ignored: there is nothing here that could produce a
// record while a request waits, so holding one would only make the tests slower.
func (b *fakeBroker) handleFetch(payload []byte) []byte {
	r := &reader{buf: payload}
	request := &fetchRequest{
		topic:     r.str(),
		partition: r.int32(),
		offset:    r.int64(),
		maxBytes:  r.int32(),
		maxWait:   r.int32(),
		minBytes:  r.int32(),
	}
	if r.err != nil {
		panic(fmt.Sprintf("fake broker could not decode FETCH: %v", r.err))
	}
	b.lastFetch.Store(request)

	b.mu.Lock()
	log := b.produced[batchKey{request.topic, request.partition}]
	b.mu.Unlock()

	highWatermark := int64(len(log))
	var stream []byte
	if request.offset < highWatermark {
		for _, record := range log[request.offset:] {
			sum := crc32.Checksum(record, crc32.MakeTable(crc32.Castagnoli))
			if b.corrupt.Load() {
				sum ^= 1
			}
			stream = binary.BigEndian.AppendUint32(stream, uint32(len(record)))
			stream = binary.BigEndian.AppendUint32(stream, sum)
			stream = append(stream, record...)
		}
	}
	if int(request.maxBytes) < len(stream) {
		stream = stream[:request.maxBytes]
	}

	body := binary.BigEndian.AppendUint64(nil, uint64(highWatermark))
	body = binary.BigEndian.AppendUint32(body, uint32(len(stream)))
	return append(body, stream...)
}

func (b *fakeBroker) handleMetadata(payload []byte) []byte {
	r := &reader{buf: payload}
	count := r.int32()
	names := make([]string, 0, count)
	for i := int32(0); i < count; i++ {
		names = append(names, r.str())
	}
	if r.err != nil {
		panic(fmt.Sprintf("fake broker could not decode METADATA: %v", r.err))
	}
	if len(names) == 0 {
		names = []string{"everything"}
	}

	b.mu.Lock()
	highWatermark := uint64(b.nextOffset)
	b.mu.Unlock()

	body := binary.BigEndian.AppendUint32(nil, uint32(len(names)))
	for _, name := range names {
		body = binary.BigEndian.AppendUint16(body, uint16(len(name)))
		body = append(body, name...)
		body = binary.BigEndian.AppendUint32(body, uint32(b.partitions))
		for partition := int32(0); partition < b.partitions; partition++ {
			body = binary.BigEndian.AppendUint32(body, uint32(partition))
			body = binary.BigEndian.AppendUint64(body, 0)
			body = binary.BigEndian.AppendUint64(body, highWatermark)
		}
	}
	return body
}
