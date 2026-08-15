package booblik

import (
	"bytes"
	"context"
	"errors"
	"strconv"
	"testing"
	"time"
)

// Holds the checksum to vectors computed by a different implementation in a different language
// (conformance/vectors/generate.py). "CRC32" names at least three different functions, all of which
// return a plausible number, so agreeing with an independent reading of the specification is the
// only property worth asserting here.
//
// If this fails, this code is wrong — not the vectors.
func TestChecksumMatchesGoldenVectors(t *testing.T) {
	rows := readVectors(t, "crc32c.tsv")
	if len(rows) == 0 {
		t.Fatal("no vectors loaded")
	}

	for _, row := range rows {
		name := row[len(row)-1]
		payload := decodeHex(t, row[0])

		want, err := strconv.ParseUint(row[1], 10, 32)
		if err != nil {
			t.Fatalf("vector %q has an unreadable checksum %q: %v", name, row[1], err)
		}
		if got := Checksum(payload); uint64(got) != want {
			t.Errorf("Checksum(%q) = %d, vectors say %d", name, got, want)
		}
	}
}

// The one number that separates CRC-32C from the CRC32 a hand reaches for first. Stated separately
// from the vector loop because this is the value quoted in the protocol document, and a failure
// here says which function is wrong rather than which vector.
func TestChecksumIsCastagnoliAndNotIEEE(t *testing.T) {
	if got := Checksum([]byte("123456789")); got != 0xE3069283 {
		t.Fatalf("check value is %#08x, CRC-32C is 0xE3069283 (0xCBF43926 would be zlib's CRC-32)", got)
	}
}

func TestFetchReturnsRecordsInOrder(t *testing.T) {
	broker := startFakeBroker(t, 1)
	payloads := [][]byte{bytes.Repeat([]byte{0xAB}, 300), {0x00}, []byte("third")}
	broker.seed("t", 0, payloads...)

	consumer := dialConsumer(t, broker, "t", 0)
	records, err := consumer.Poll(context.Background())
	if err != nil {
		t.Fatalf("poll: %v", err)
	}

	if len(records) != len(payloads) {
		t.Fatalf("read %d records, seeded %d", len(records), len(payloads))
	}
	for i, want := range payloads {
		if !bytes.Equal(records[i], want) {
			t.Errorf("record %d is %x, seeded %x", i, records[i], want)
		}
	}
	if consumer.Position() != int64(len(payloads)) {
		t.Errorf("position is %d after reading %d records", consumer.Position(), len(payloads))
	}
	if consumer.HighWatermark() != int64(len(payloads)) {
		t.Errorf("high watermark is %d, the log ends at %d", consumer.HighWatermark(), len(payloads))
	}
}

// The steady state of a consumer that is keeping up, and the one it must not read as the end of the
// log: no records, no error, and the position where it was.
func TestFetchAtTheHighWatermarkIsEmptyAndNotAnError(t *testing.T) {
	broker := startFakeBroker(t, 1)
	broker.seed("t", 0, []byte("one"))

	consumer := dialConsumer(t, broker, "t", 0)
	if _, err := consumer.Poll(context.Background()); err != nil {
		t.Fatalf("first poll: %v", err)
	}

	records, err := consumer.Poll(context.Background())
	if err != nil {
		t.Fatalf("polling at the high watermark: %v", err)
	}
	if len(records) != 0 {
		t.Fatalf("read %d records past the end of the log", len(records))
	}
	if consumer.Position() != 1 || consumer.Lag() != 0 {
		t.Fatalf("position %d, lag %d — a caught-up consumer moved", consumer.Position(), consumer.Lag())
	}
}

// maxBytes cuts on a byte boundary, so a full response normally ends inside a record. The fragment
// must be dropped and the position must stop before it — a consumer that counts the fragment
// corrupts data, and one that treats a short response as the end of the log stalls for ever.
func TestTruncatedTailIsDroppedAndRefetched(t *testing.T) {
	broker := startFakeBroker(t, 1)
	payloads := [][]byte{bytes.Repeat([]byte("A"), 100), bytes.Repeat([]byte("B"), 100)}
	broker.seed("t", 0, payloads...)

	consumer := dialConsumer(t, broker, "t", 0)
	// One whole record is 8 bytes of header and 100 of payload; 150 stops inside the second.
	consumer.MaxBytes = 150

	first, err := consumer.Poll(context.Background())
	if err != nil {
		t.Fatalf("first poll: %v", err)
	}
	if len(first) != 1 || !bytes.Equal(first[0], payloads[0]) {
		t.Fatalf("expected exactly the first whole record, got %d records", len(first))
	}
	if consumer.Position() != 1 {
		t.Fatalf("position is %d, the partial record must not be counted", consumer.Position())
	}

	second, err := consumer.Poll(context.Background())
	if err != nil {
		t.Fatalf("second poll: %v", err)
	}
	if len(second) != 1 || !bytes.Equal(second[0], payloads[1]) {
		t.Fatalf("the dropped record did not come back whole: got %d records", len(second))
	}
}

// A response that stops inside the *header* of the next record, rather than inside its payload.
// Same truncation, different branch: there is no size field to read, so it is found by having bytes
// left over instead of by a length that does not fit.
func TestResponseStoppingInsideARecordHeaderIsTruncation(t *testing.T) {
	broker := startFakeBroker(t, 1)
	broker.seed("t", 0, bytes.Repeat([]byte("A"), 20), bytes.Repeat([]byte("B"), 20))

	consumer := dialConsumer(t, broker, "t", 0)
	// 28 bytes is the first record whole, then 4 bytes into the second record's 8-byte header.
	consumer.MaxBytes = 32

	records, err := consumer.Poll(context.Background())
	if err != nil {
		t.Fatalf("poll: %v", err)
	}
	if len(records) != 1 {
		t.Fatalf("read %d records, expected the one whole record", len(records))
	}
	if consumer.Position() != 1 {
		t.Fatalf("position is %d, expected 1", consumer.Position())
	}
}

// The stall that does not resolve itself. A record larger than MaxBytes arrives as a truncated tail
// with nothing before it, so every retry makes the identical request — the consumer keeps running,
// reports nothing, and never advances. It has to be an error, and it has to carry both numbers,
// because raising MaxBytes is the only thing that helps.
func TestRecordLargerThanMaxBytesIsReportedRatherThanRetried(t *testing.T) {
	broker := startFakeBroker(t, 1)
	broker.seed("t", 0, bytes.Repeat([]byte("A"), 500))

	consumer := dialConsumer(t, broker, "t", 0)
	consumer.MaxBytes = 100

	_, err := consumer.Poll(context.Background())
	if !errors.Is(err, ErrRecordExceedsMaxBytes) {
		t.Fatalf("expected ErrRecordExceedsMaxBytes, got %v", err)
	}

	var stuck *RecordExceedsMaxBytesError
	if !errors.As(err, &stuck) {
		t.Fatalf("error does not carry the numbers: %v", err)
	}
	if stuck.RecordBytes != 500 || stuck.MaxBytes != 100 || stuck.Offset != 0 {
		t.Errorf("error says offset %d, %d bytes against MaxBytes %d",
			stuck.Offset, stuck.RecordBytes, stuck.MaxBytes)
	}
	if consumer.Position() != 0 {
		t.Errorf("position moved to %d past a record that was never read", consumer.Position())
	}
}

// The client is the only party that can catch this: on the zero-copy path the broker never touches
// the record bytes it sends. A client that skips verification returns the damaged bytes and looks
// perfectly healthy doing it.
func TestCorruptRecordIsRejected(t *testing.T) {
	broker := startFakeBroker(t, 1)
	broker.seed("t", 0, []byte("payload"))
	broker.corrupt.Store(true)

	consumer := dialConsumer(t, broker, "t", 0)
	records, err := consumer.Poll(context.Background())
	if !errors.Is(err, ErrCorruptRecord) {
		t.Fatalf("expected ErrCorruptRecord, got %d records and error %v", len(records), err)
	}

	var corrupt *CorruptRecordError
	if !errors.As(err, &corrupt) {
		t.Fatalf("error does not say which record: %v", err)
	}
	if corrupt.Offset != 0 || corrupt.Stored == corrupt.Computed {
		t.Errorf("expected a mismatch at offset 0, got %+v", corrupt)
	}
}

// A truncated tail is not corruption, and the two are one line apart in the decoder: the length
// check has to come before the checksum, or every full response is an alarm.
func TestTruncationIsNotReportedAsCorruption(t *testing.T) {
	broker := startFakeBroker(t, 1)
	broker.seed("t", 0, bytes.Repeat([]byte("A"), 100), bytes.Repeat([]byte("B"), 100))

	consumer := dialConsumer(t, broker, "t", 0)
	consumer.MaxBytes = 150

	if _, err := consumer.Poll(context.Background()); errors.Is(err, ErrCorruptRecord) {
		t.Fatalf("a partial record was reported as corruption: %v", err)
	} else if err != nil {
		t.Fatalf("poll: %v", err)
	}
}

// v2 always, including when nothing is being waited for — one code path, so the waiting fields are
// not exercised only in the branch nobody debugs. Asserted from the broker's side, which is the
// only place that can tell what was actually sent.
func TestFetchAlwaysSendsVersionTwoWithTheWaitingFields(t *testing.T) {
	broker := startFakeBroker(t, 1)
	broker.seed("t", 0, []byte("one"))

	conn := dial(t, broker)
	_, err := conn.Fetch(context.Background(), FetchRequest{
		Topic:     "t",
		Partition: 0,
		Offset:    0,
		MaxBytes:  1 << 20,
		MaxWait:   250 * time.Millisecond,
		MinBytes:  64,
	})
	if err != nil {
		t.Fatalf("fetch: %v", err)
	}

	if version := broker.lastVersion.Load(); version != int32(versionV2) {
		t.Errorf("FETCH went out as v%d, the waiting fields only exist in v2", version)
	}
	request := broker.lastFetch.Load()
	if request == nil {
		t.Fatal("the broker decoded no FETCH at all")
	}
	if request.maxWait != 250 || request.minBytes != 64 {
		t.Errorf("broker read maxWait=%d minBytes=%d, sent 250 and 64", request.maxWait, request.minBytes)
	}
	if request.topic != "t" || request.partition != 0 || request.offset != 0 || request.maxBytes != 1<<20 {
		t.Errorf("broker read %+v, which is not what was sent", request)
	}
}

// A wait long enough to overflow the int32 it is sent as. Clamped rather than truncated: the
// truncation of 25 days is a wait of a few milliseconds, and at exactly the wrong duration a
// negative one.
func TestAbsurdlyLongWaitIsClamped(t *testing.T) {
	if got := waitMillis(40 * 24 * time.Hour); got != 2147483647 {
		t.Errorf("a 40-day wait became %d milliseconds", got)
	}
	if got := waitMillis(-time.Second); got != 0 {
		t.Errorf("a negative wait became %d milliseconds", got)
	}
}

func TestRefusalReachesTheCaller(t *testing.T) {
	broker := startFakeBroker(t, 1)
	broker.refuse(CodeOffsetOutOfRange)

	consumer := dialConsumer(t, broker, "t", 0)
	_, err := consumer.Poll(context.Background())
	if !errors.Is(err, ErrOffsetOutOfRange) {
		t.Fatalf("expected OFFSET_OUT_OF_RANGE, got %v", err)
	}
}

func TestSeekToMovesThePosition(t *testing.T) {
	broker := startFakeBroker(t, 1)
	broker.seed("t", 0, []byte("zero"), []byte("one"), []byte("two"))

	consumer := dialConsumer(t, broker, "t", 0)
	consumer.SeekTo(2)

	records, err := consumer.Poll(context.Background())
	if err != nil {
		t.Fatalf("poll: %v", err)
	}
	if len(records) != 1 || !bytes.Equal(records[0], []byte("two")) {
		t.Fatalf("reading from offset 2 gave %d records: %q", len(records), records)
	}
}

func TestRecordsIteratorYieldsEveryRecordOnce(t *testing.T) {
	broker := startFakeBroker(t, 1)
	seeded := [][]byte{[]byte("a"), []byte("b"), []byte("c")}
	broker.seed("t", 0, seeded...)

	consumer := dialConsumer(t, broker, "t", 0)
	consumer.MaxWait = 0

	var read [][]byte
	for record, err := range consumer.Records(context.Background()) {
		if err != nil {
			t.Fatalf("iterating: %v", err)
		}
		read = append(read, record)
		if len(read) == len(seeded) {
			break
		}
	}

	for i, want := range seeded {
		if !bytes.Equal(read[i], want) {
			t.Errorf("record %d is %q, seeded %q", i, read[i], want)
		}
	}
}

// The loop has no natural end — a partition has no end, only a place it has not been written to
// yet — so cancelling the context is how it stops, and it stops without reporting the cancellation
// as a failure of anything.
func TestRecordsIteratorStopsOnCancellation(t *testing.T) {
	broker := startFakeBroker(t, 1)
	broker.seed("t", 0, []byte("only"))

	consumer := dialConsumer(t, broker, "t", 0)
	consumer.MaxWait = 0

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})

	go func() {
		defer close(done)
		for _, err := range consumer.Records(ctx) {
			if err != nil {
				t.Errorf("cancellation surfaced as an error: %v", err)
			}
			cancel()
		}
	}()

	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("the iterator kept fetching after its context was cancelled")
	}
}

// Errors end the iteration, and end it exactly once: yielding a second time after the caller has
// been told the loop is over is the mistake a hand-written range-over-func makes.
func TestRecordsIteratorEndsOnError(t *testing.T) {
	broker := startFakeBroker(t, 1)
	broker.refuse(CodeOffsetOutOfRange)

	consumer := dialConsumer(t, broker, "t", 0)
	consumer.MaxWait = 0

	errorsSeen := 0
	for _, err := range consumer.Records(context.Background()) {
		if err == nil {
			t.Fatal("a record arrived from a refused fetch")
		}
		errorsSeen++
	}
	if errorsSeen != 1 {
		t.Fatalf("the iterator yielded %d errors, expected exactly one", errorsSeen)
	}
}

func dialConsumer(t *testing.T, broker *fakeBroker, topic string, partition int32) *Consumer {
	t.Helper()

	consumer := dial(t, broker).Consumer(topic, partition, 0)
	// The fixture answers immediately whatever it is asked, so waiting would only be time spent.
	consumer.MaxWait = 0
	return consumer
}
