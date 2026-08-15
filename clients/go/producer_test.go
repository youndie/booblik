package booblik

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"
)

func testConfig(linger time.Duration, maxBatch int) ProducerConfig {
	return ProducerConfig{
		MaxBatchSize:   maxBatch,
		Linger:         linger,
		Ack:            AckWritten,
		RequestTimeout: 5 * time.Second,
	}
}

// The regression test the JVM client needed twice, ported before it was needed here.
//
// Records arrive one per linger window, so every one of them is delivered by the timer rather than
// by a full batch — which is exactly the interleaving where the JVM accumulator lost a record: its
// timeout **cancelled** the pending receive, and a cancelled receive could take an element off the
// channel and then drop it. Whoever was waiting on that record's offset then waited for ever while
// the accumulator carried on serving everybody else.
//
// Go's select cannot do that: it commits to one case. The test exists anyway, because "cannot
// happen in this language" is a property of the current implementation, not of the API.
func TestProducerLosesNoRecordsAcrossLingerWindows(t *testing.T) {
	broker := startFakeBroker(t, 3)
	conn := dial(t, broker)

	const linger = time.Millisecond
	const rounds = 300

	producer := NewProducer(conn, testConfig(linger, 100))
	defer func() { _ = producer.Close() }()

	promises := make([]*Promise, rounds)
	for i := range rounds {
		promise, err := producer.Send(context.Background(), "orders", 0, []byte(fmt.Sprintf("r-%d", i)))
		if err != nil {
			t.Fatalf("send %d: %v", i, err)
		}
		promises[i] = promise
		// Sleeping the window, not less: this is what puts each arrival next to a firing timer.
		time.Sleep(linger)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	for i, promise := range promises {
		offset, err := promise.Await(ctx)
		if err != nil {
			t.Fatalf("record %d never got an offset: %v", i, err)
		}
		if offset != int64(i) {
			t.Fatalf("record %d got offset %d", i, offset)
		}
	}

	if arrived := len(broker.recordsIn("orders", 0)); arrived != rounds {
		t.Fatalf("broker received %d records of %d", arrived, rounds)
	}

	// And the interleaving actually happened. The bound is derived rather than chosen: a
	// batch-driven run would take exactly rounds/maxBatchSize requests, and anything above that is
	// the timer. A larger threshold measures the host's timer granularity instead — the Java port
	// of this assertion passed on macOS and failed on Linux at ten requests.
	if requests := broker.requestCount(); requests <= rounds/100 {
		t.Fatalf("%d records went out in %d requests, so the window never fired", rounds, requests)
	}
}

// A full batch goes at once rather than waiting out the window, which is what makes a long linger
// safe under load instead of a latency floor.
func TestFullBatchDoesNotWaitForTheWindow(t *testing.T) {
	broker := startFakeBroker(t, 3)
	conn := dial(t, broker)

	producer := NewProducer(conn, testConfig(time.Hour, 10))
	defer func() { _ = producer.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	var last *Promise
	for i := range 10 {
		promise, err := producer.Send(ctx, "orders", 0, []byte(fmt.Sprintf("r-%d", i)))
		if err != nil {
			t.Fatalf("send %d: %v", i, err)
		}
		last = promise
	}

	// An hour of linger is still pending; only the batch being full can complete this.
	if _, err := last.Await(ctx); err != nil {
		t.Fatalf("a full batch waited for the linger window: %v", err)
	}
	if requests := broker.requestCount(); requests != 1 {
		t.Fatalf("ten records with a batch size of ten took %d requests", requests)
	}
}

// Records for different partitions are different batches, because a request addresses one partition
// — a partition being what has one writer.
func TestPartitionsAccumulateSeparately(t *testing.T) {
	broker := startFakeBroker(t, 3)
	conn := dial(t, broker)

	producer := NewProducer(conn, testConfig(2*time.Millisecond, 100))
	defer func() { _ = producer.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	for partition := range int32(3) {
		if _, err := producer.Send(ctx, "orders", partition, []byte("x")); err != nil {
			t.Fatalf("send to %d: %v", partition, err)
		}
	}
	if err := producer.Flush(ctx); err != nil {
		t.Fatalf("flush: %v", err)
	}

	for partition := range int32(3) {
		if got := len(broker.recordsIn("orders", partition)); got != 1 {
			t.Errorf("partition %d received %d records, expected 1", partition, got)
		}
	}
	if requests := broker.requestCount(); requests != 3 {
		t.Fatalf("three partitions took %d requests, expected one each", requests)
	}
}

func TestFlushDeliversWithoutWaitingOutTheWindow(t *testing.T) {
	broker := startFakeBroker(t, 3)
	conn := dial(t, broker)

	producer := NewProducer(conn, testConfig(time.Hour, 100))
	defer func() { _ = producer.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	promise, err := producer.Send(ctx, "orders", 0, []byte("x"))
	if err != nil {
		t.Fatalf("send: %v", err)
	}
	if err := producer.Flush(ctx); err != nil {
		t.Fatalf("flush: %v", err)
	}
	if _, err := promise.Await(ctx); err != nil {
		t.Fatalf("flush returned before the record was answered for: %v", err)
	}
}

// Close is a flush: records queued when it is called still go out. Dropping them would make every
// clean shutdown a silent data loss.
func TestCloseFlushesWhatIsQueued(t *testing.T) {
	broker := startFakeBroker(t, 3)
	conn := dial(t, broker)

	producer := NewProducer(conn, testConfig(time.Hour, 100))

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if _, err := producer.Send(ctx, "orders", 0, []byte("x")); err != nil {
		t.Fatalf("send: %v", err)
	}
	if err := producer.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	if got := len(broker.recordsIn("orders", 0)); got != 1 {
		t.Fatalf("close dropped the queued record: broker has %d", got)
	}
	if _, err := producer.Send(ctx, "orders", 0, []byte("y")); err != ErrProducerClosed {
		t.Fatalf("sending after Close returned %v", err)
	}
}

// With AckNone there is no offset, and the promise has to say so rather than hanging: nothing is
// ever going to complete it from the wire.
func TestAckNoneCompletesWithUnknownOffset(t *testing.T) {
	broker := startFakeBroker(t, 3)
	conn := dial(t, broker)

	config := testConfig(time.Millisecond, 100)
	config.Ack = AckNone
	producer := NewProducer(conn, config)
	defer func() { _ = producer.Close() }()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	promise, err := producer.Send(ctx, "orders", 0, []byte("x"))
	if err != nil {
		t.Fatalf("send: %v", err)
	}
	offset, err := promise.Await(ctx)
	if err != nil {
		t.Fatalf("await: %v", err)
	}
	if offset != OffsetUnknown {
		t.Fatalf("AckNone reported offset %d, expected OffsetUnknown", offset)
	}
}

// Send racing Close must return an error, never take the caller's process down with it.
//
// The first version of this client signalled shutdown by closing the mailbox, and Send selected
// between writing to it and observing shutdown. Go picks at random among the ready cases, and a
// send on a closed channel is ready — so this crashed, with a probability rather than reliably.
// `TestCloseFlushesWhatIsQueued` does one Send after Close and passed nearly always; it took four
// client gates running back to back to move the timing enough to catch it.
//
// Hence this: many senders, many rounds, and -race on top.
func TestSendRacingCloseNeverPanics(t *testing.T) {
	for round := 0; round < 20; round++ {
		broker := startFakeBroker(t, 3)
		conn := dial(t, broker)
		producer := NewProducer(conn, testConfig(time.Millisecond, 100))

		var senders sync.WaitGroup
		for sender := 0; sender < 4; sender++ {
			senders.Add(1)
			go func() {
				defer senders.Done()
				for attempt := 0; attempt < 25; attempt++ {
					// An error is the correct outcome after Close; a panic is not an outcome.
					if _, err := producer.Send(context.Background(), "orders", 0, []byte("x")); err != nil {
						return
					}
				}
			}()
		}

		if err := producer.Close(); err != nil {
			t.Fatalf("round %d: close: %v", round, err)
		}
		senders.Wait()
	}
}
