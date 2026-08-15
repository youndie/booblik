package booblik

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"hash/crc32"
	"iter"
	"math"
	"time"
)

// The consumer half: FETCH, the checksum, and a position.
//
// It is the expensive half of a client, and none of what makes it expensive is visible in the
// protocol. A publisher that gets something wrong hears about it — the broker answers with an error
// code. A consumer that gets something wrong returns plausible bytes, or returns nothing and calls
// it the end of the log. Every decision in this file is aimed at one of those.

// castagnoli is built once: MakeTable allocates and fills 1 KiB, and doing that per record would be
// most of the cost of verifying a small one.
var castagnoli = crc32.MakeTable(crc32.Castagnoli)

// Checksum is CRC-32C (Castagnoli) over record — the sum the broker stored beside it when it wrote
// the record, and the only thing that can be checked about a fetched record's bytes.
//
// **Not zlib's CRC-32**, which Go also provides as crc32.IEEE and which is what an autocompleted
// crc32.ChecksumIEEE gives you. Both are called "CRC32", both return a plausible number, and a
// client using the wrong one rejects every record it reads. The check value for "123456789" is
// 0xE3069283; conformance/vectors/crc32c.tsv pins this, and TestChecksumMatchesGoldenVectors holds
// this function to it.
//
// Go is the language where this is free — hash/crc32 has the Castagnoli table and, on amd64 and
// arm64, the hardware instruction behind it. Python, Node and .NET have to bring their own.
func Checksum(record []byte) uint32 { return crc32.Checksum(record, castagnoli) }

// ErrCorruptRecord is a record whose bytes do not match the checksum stored with them.
//
// The client is the only party that can notice. On the zero-copy read path the broker sends segment
// bytes to the socket without looking at them — that is what zero-copy means — so the sum is
// computed once at write time to protect the **disk**, and verified once at read time, here. A
// client that skips it silently switches off the project's only defence against a corrupted log.
var ErrCorruptRecord = errors.New("booblik: record fails its checksum")

// ErrRecordExceedsMaxBytes is the second of the two ways a consumer stalls, and the one that does
// not resolve itself: a record larger than MaxBytes can never be delivered whole, so the response
// is a truncated tail with nothing before it, and a client that drops the tail and retries makes
// exactly the same request for ever.
//
// It is reported rather than retried because no retry can help — MaxBytes has to grow. Not to be
// confused with ErrRecordTooLarge, which is the broker refusing to **store** a record too big for a
// segment; this one is the reader's own limit, chosen by the reader.
var ErrRecordExceedsMaxBytes = errors.New("booblik: record does not fit in MaxBytes")

// CorruptRecordError says which record failed and by how much.
type CorruptRecordError struct {
	// Offset of the record, so a corrupted log can be inspected rather than merely feared.
	Offset   int64
	Stored   uint32
	Computed uint32
}

func (e *CorruptRecordError) Error() string {
	return fmt.Sprintf(
		"booblik: record at offset %d fails its checksum: stored %#08x, computed %#08x",
		e.Offset, e.Stored, e.Computed,
	)
}

func (e *CorruptRecordError) Unwrap() error { return ErrCorruptRecord }

// RecordExceedsMaxBytesError carries both numbers, because the fix is to raise one above the other.
type RecordExceedsMaxBytesError struct {
	Offset      int64
	RecordBytes int32
	MaxBytes    int32
}

func (e *RecordExceedsMaxBytesError) Error() string {
	return fmt.Sprintf(
		"booblik: record at offset %d needs %d bytes and MaxBytes is %d, so it can never be read whole",
		e.Offset, e.RecordBytes, e.MaxBytes,
	)
}

func (e *RecordExceedsMaxBytesError) Unwrap() error { return ErrRecordExceedsMaxBytes }

// FetchRequest is one read of one partition.
//
// A struct rather than seven positional arguments, and the two waiting fields are why: MaxWait and
// MinBytes are almost always left alone, and a call site reading Fetch(ctx, t, 0, 0, 1<<20, 0, 0)
// hides which zero means what.
type FetchRequest struct {
	Topic     string
	Partition int32
	// Offset to read from. Exactly the high watermark is legal and returns nothing; past it is
	// OFFSET_OUT_OF_RANGE.
	Offset int64
	// MaxBytes bounds the response **in bytes, not in records**, so the response can stop inside a
	// record. See Fetched.Truncated.
	MaxBytes int32
	// MaxWait is how long the broker holds a request that has nothing to answer with. Zero returns
	// immediately, which turns a caught-up consumer into a busy loop; the broker clamps large
	// values (60s) rather than letting a caller pin a connection for as long as it likes.
	MaxWait time.Duration
	// MinBytes holds the response until this much has accumulated. 0 and 1 both mean "whatever is
	// there". Greater than MaxBytes is CORRUPT_REQUEST — a request that can never be satisfied —
	// and that is left to the broker rather than checked here, so there is one place that decides
	// it instead of two that can disagree.
	MinBytes int32
}

// Fetched is one FETCH response, already unframed and checksum-verified.
type Fetched struct {
	// HighWatermark is the first offset that does not exist yet, as of this response.
	HighWatermark int64
	// Records point **into the response frame**, without copying. The frame stays alive as long as
	// any record from it does, so a caller keeping one record out of a megabyte response keeps the
	// megabyte. Copy what you retain.
	Records [][]byte
	// Truncated says the response ended inside a record. Routine rather than exceptional: MaxBytes
	// cuts on a byte boundary, so a full response normally ends this way. The partial tail is
	// dropped and re-read from its start by the next fetch.
	Truncated bool
	// TruncatedRecordBytes is how big the dropped record is, when its header made it into the
	// response. Zero when the response stopped inside the header itself.
	TruncatedRecordBytes int32
}

// Fetch reads records from one partition.
//
// Always emits v2, including when nothing is being waited for. One code path rather than two: a
// client that switched versions depending on its arguments would exercise v1 only in the branch
// nobody debugs. The broker still decodes v1 for anybody else's client, which is what version
// support is for. The Kotlin client makes the same choice for the same reason.
func (c *Conn) Fetch(ctx context.Context, request FetchRequest) (*Fetched, error) {
	defer c.withContext(ctx)()

	payload := make([]byte, 0, 2+len(request.Topic)+4+8+4+4+4)
	payload = binary.BigEndian.AppendUint16(payload, uint16(len(request.Topic)))
	payload = append(payload, request.Topic...)
	payload = binary.BigEndian.AppendUint32(payload, uint32(request.Partition))
	payload = binary.BigEndian.AppendUint64(payload, uint64(request.Offset))
	payload = binary.BigEndian.AppendUint32(payload, uint32(request.MaxBytes))
	payload = binary.BigEndian.AppendUint32(payload, uint32(waitMillis(request.MaxWait)))
	payload = binary.BigEndian.AppendUint32(payload, uint32(request.MinBytes))

	correlation, err := c.send(apiFetch, versionV2, payload)
	if err != nil {
		return nil, err
	}
	body, err := c.receive(correlation)
	if err != nil {
		return nil, err
	}
	return decodeFetch(body, request.Offset)
}

// waitMillis clamps rather than truncating into int32, which would turn a caller's absurdly long
// wait into a short one — or, at exactly the wrong duration, into a negative one.
func waitMillis(wait time.Duration) int32 {
	millis := wait.Milliseconds()
	switch {
	case millis <= 0:
		return 0
	case millis > math.MaxInt32:
		return math.MaxInt32
	default:
		return int32(millis)
	}
}

func decodeFetch(body []byte, offset int64) (*Fetched, error) {
	const headerBytes = 8 + 4 // highWatermark, payloadBytes
	if len(body) < headerBytes {
		return nil, fmt.Errorf("booblik: FETCH response is %d bytes, expected at least %d", len(body), headerBytes)
	}

	result := &Fetched{HighWatermark: int64(binary.BigEndian.Uint64(body[0:]))}
	promised := int(int32(binary.BigEndian.Uint32(body[8:])))
	payload := body[headerBytes:]

	// The frame length already bounds the payload, so this field is redundant — and that is exactly
	// what makes it worth checking. It is computed before the transfer starts, while the bytes come
	// from `transferTo` afterwards and in an unpredictable number of pieces; a disagreement means
	// the two halves of the response were produced from different states of the log.
	if promised != len(payload) {
		return nil, fmt.Errorf(
			"booblik: FETCH promised %d payload bytes and the frame carries %d", promised, len(payload),
		)
	}

	for len(payload) >= recordHeaderBytes {
		size := int(int32(binary.BigEndian.Uint32(payload[0:])))
		stored := binary.BigEndian.Uint32(payload[4:])

		// A whole header is either there or not — parsing always resumes on a record boundary — so a
		// non-positive size is a malformed frame and not a truncated tail. Empty records cannot be
		// stored at all, which is why the broker refuses them.
		if size <= 0 {
			return nil, fmt.Errorf("booblik: record header at offset %d says %d bytes",
				offset+int64(len(result.Records)), size)
		}
		if size > len(payload)-recordHeaderBytes {
			result.Truncated = true
			result.TruncatedRecordBytes = int32(size)
			return result, nil
		}

		record := payload[recordHeaderBytes : recordHeaderBytes+size]
		if computed := Checksum(record); computed != stored {
			// After the length check, never before it: a truncated tail is not corruption, and
			// reporting it as such would turn the most ordinary response there is into an alarm.
			return nil, &CorruptRecordError{
				Offset:   offset + int64(len(result.Records)),
				Stored:   stored,
				Computed: computed,
			}
		}
		result.Records = append(result.Records, record)
		payload = payload[recordHeaderBytes+size:]
	}

	// Fewer bytes left than a record header: the response stopped inside the header of the next
	// record, which is the same truncation with nothing to report about its size.
	result.Truncated = len(payload) > 0
	return result, nil
}

// Consumer reads one partition, forward, from wherever it is told to start.
//
// **The position lives here, not in the broker.** That is half the reason this project has no
// consumer groups, no coordinator and no committed-offset storage: an offset is a number the reader
// already knows, and asking a broker to remember it is what drags in cluster consensus. The cost is
// that a restarting consumer has to be told where to resume — Position is the number to write down,
// and writing it down after the records are dealt with rather than before is what makes a restart
// re-deliver instead of skip.
//
// Not safe for concurrent use: every Poll advances the position, and the connection underneath
// matches responses to requests in the order they were sent. One consumer, one partition, one
// goroutine.
type Consumer struct {
	conn      *Conn
	topic     string
	partition int32
	position  int64

	highWatermark int64

	// MaxBytes bounds a response. Larger means fewer round trips and a bigger buffer per fetch;
	// smaller risks ErrRecordExceedsMaxBytes on a large record.
	MaxBytes int32
	// MaxWait is how long the broker may hold a fetch that has nothing to answer. The default is
	// what keeps a caught-up consumer from becoming a busy loop against the broker; setting it to
	// zero returns immediately and asks the caller to do its own waiting.
	MaxWait time.Duration
	// MinBytes holds a response until this much has accumulated, trading latency for round trips.
	MinBytes int32
}

const (
	// 1 MiB: large enough that a fetch is worth its round trip, small enough that one response
	// cannot dominate a small heap. The Kotlin client uses the same number.
	DefaultMaxBytes int32 = 1 << 20
	// DefaultMaxWait is five seconds, and the number is a consequence of there being one partition
	// per consumer here. A caught-up consumer with no wait asks again immediately and gets nothing,
	// which is a busy loop dressed as a poll; five seconds turns that into one request per five
	// seconds while leaving new records a wake-up that is still immediate — the broker answers as
	// soon as a record lands, not when the timer runs out.
	DefaultMaxWait = 5 * time.Second
)

// Consumer starts reading topic/partition at start.
//
// Reading "from the beginning" means starting at the partition's LogStartOffset from METADATA, not
// at zero: zero is OFFSET_OUT_OF_RANGE on any topic that has ever dropped a segment to retention.
// Reading "only what is new" means starting at its HighWatermark.
func (c *Conn) Consumer(topic string, partition int32, start int64) *Consumer {
	return &Consumer{
		conn:      c,
		topic:     topic,
		partition: partition,
		position:  start,
		MaxBytes:  DefaultMaxBytes,
		MaxWait:   DefaultMaxWait,
	}
}

// Position is the offset of the next record this consumer will read. This is the number to persist.
func (c *Consumer) Position() int64 { return c.position }

// SeekTo moves the read position. Anything fetched and not yet returned is simply forgotten.
//
// Named SeekTo rather than Seek, and not by preference: `go vet` rejects a Seek that is not
// io.Seeker's Seek(int64, int) (int64, error), because a type that half-implements a standard
// interface is passed to something expecting the whole of it. The Kotlin client calls this seek().
func (c *Consumer) SeekTo(offset int64) { c.position = offset }

// HighWatermark is where the log ended at the last successful Poll, and zero before the first one.
// It is a snapshot rather than a live number: by the time it is read, the log may have grown.
func (c *Consumer) HighWatermark() int64 { return c.highWatermark }

// Lag is how many records this consumer was behind at the last Poll. Same snapshot caveat.
func (c *Consumer) Lag() int64 {
	if c.highWatermark <= c.position {
		return 0
	}
	return c.highWatermark - c.position
}

// Poll reads the next records and advances Position past them.
//
// **An empty result is not the end of anything.** A consumer that has caught up polls at the high
// watermark and is answered with no records, and that is the steady state of every consumer that is
// keeping up — treating it as the end of the log is how a consumer stops for ever without erroring.
//
// The position advances past whole records only. A response can stop inside a record, because
// MaxBytes cuts on a byte boundary; the partial tail is dropped and the next Poll asks for that
// record again from its start. The broker will not do this for us — finding the record boundary
// means parsing the batch, which is the work the zero-copy read path exists to avoid.
func (c *Consumer) Poll(ctx context.Context) ([][]byte, error) {
	answer, err := c.conn.Fetch(ctx, FetchRequest{
		Topic:     c.topic,
		Partition: c.partition,
		Offset:    c.position,
		MaxBytes:  c.MaxBytes,
		MaxWait:   c.MaxWait,
		MinBytes:  c.MinBytes,
	})
	if err != nil {
		return nil, err
	}

	// Nothing whole, and something partial: the next record is bigger than this consumer is willing
	// to receive, so retrying is what a stall looks like from the inside. Reported rather than
	// retried — the numbers are in the error because raising MaxBytes is the only fix.
	if len(answer.Records) == 0 && answer.Truncated {
		return nil, &RecordExceedsMaxBytesError{
			Offset:      c.position,
			RecordBytes: answer.TruncatedRecordBytes,
			MaxBytes:    c.MaxBytes,
		}
	}

	c.highWatermark = answer.HighWatermark
	c.position += int64(len(answer.Records))
	return answer.Records, nil
}

// Records is Poll as a range-over-func iterator: it yields records one at a time and keeps fetching
// for as long as ctx lives.
//
//	for record, err := range consumer.Records(ctx) {
//		if err != nil {
//			return err
//		}
//		handle(record)
//	}
//
// **The loop does not end on its own**, and that is the shape of the thing rather than an oversight:
// a partition has no end, only a place where it has not been written to yet. Cancel ctx to stop.
// Cancellation stops the iteration without yielding an error, because a caller that cancelled a
// context does not need to be told about it; anything else is yielded once, and the iteration ends
// there.
//
// Not a channel, which is the usual first instinct in Go and is worse here in three ways: the
// records would arrive with no way to return the error that ended them, stopping early would leak
// the goroutine feeding it, and a buffered channel would read ahead of what the caller has handled —
// which silently moves the position past records nobody has processed. An iterator has none of
// those: it runs on the caller's goroutine, at the caller's pace.
//
// Position advances a whole fetch at a time, not a record at a time. Breaking out mid-batch and
// persisting Position skips the rest of that batch, so persist after the loop, or count what was
// handled.
func (c *Consumer) Records(ctx context.Context) iter.Seq2[[]byte, error] {
	return func(yield func([]byte, error) bool) {
		for {
			records, err := c.Poll(ctx)
			if err != nil {
				// A cancelled context reaches here as whatever the socket did about the deadline
				// moving into the past, which is a network error saying nothing useful. The caller
				// asked for this, so it ends the loop and is not reported as a failure.
				if ctx.Err() != nil {
					return
				}
				yield(nil, err)
				return
			}
			for _, record := range records {
				if !yield(record, nil) {
					return
				}
			}
		}
	}
}
