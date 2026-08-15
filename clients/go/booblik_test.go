package booblik

import (
	"bytes"
	"context"
	"errors"
	"testing"
	"time"
)

func dial(t *testing.T, broker *fakeBroker) *Conn {
	t.Helper()

	conn, err := Dial(context.Background(), broker.address())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	return conn
}

func TestProduceRoundTrip(t *testing.T) {
	broker := startFakeBroker(t, 3)
	conn := dial(t, broker)

	// Every byte value, because an encoding that damages the payload usually damages the high half
	// of it and leaves ASCII intact.
	records := [][]byte{allBytes(), []byte("second"), {0x00}}

	result, err := conn.Produce(context.Background(), "orders", 2, records, AckWritten)
	if err != nil {
		t.Fatalf("produce: %v", err)
	}
	if result.BaseOffset != 0 || result.LogEndOffset != 3 {
		t.Fatalf("offsets %d..%d, expected 0..3", result.BaseOffset, result.LogEndOffset)
	}

	arrived := broker.recordsIn("orders", 2)
	if len(arrived) != len(records) {
		t.Fatalf("broker decoded %d records, sent %d", len(arrived), len(records))
	}
	for i := range records {
		if !bytes.Equal(arrived[i], records[i]) {
			t.Errorf("record %d changed in transit", i)
		}
	}
}

// AckNone answers nothing at all, and a client that reads a response after it blocks for ever. The
// bound here is what makes this a test rather than a hang.
func TestAckNoneDoesNotWaitForAnAnswer(t *testing.T) {
	broker := startFakeBroker(t, 3)
	conn := dial(t, broker)

	returned := make(chan error, 1)
	go func() {
		result, err := conn.Produce(context.Background(), "orders", 0, [][]byte{[]byte("x")}, AckNone)
		if err == nil && result != nil {
			err = errors.New("AckNone returned a result, but no offset exists to report")
		}
		returned <- err
	}()

	select {
	case err := <-returned:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Produce waited for a response that is never coming")
	}
}

func TestBrokerRefusalIsAnErrorAndKeepsTheConnection(t *testing.T) {
	broker := startFakeBroker(t, 3)
	broker.refuse(CodeUnknownTopicOrPartition)
	conn := dial(t, broker)

	_, err := conn.Produce(context.Background(), "nope", 0, [][]byte{[]byte("x")}, AckWritten)
	if !errors.Is(err, ErrUnknownTopicOrPartition) {
		t.Fatalf("expected ErrUnknownTopicOrPartition, got %v", err)
	}

	// Framing was intact, so the connection is still usable — only a frame length out of range
	// closes one. A client that tore the socket down here would turn a refusal into an outage.
	broker.refuse(CodeNone)
	if _, err := conn.Produce(context.Background(), "orders", 0, [][]byte{[]byte("x")}, AckWritten); err != nil {
		t.Fatalf("connection was unusable after a refusal: %v", err)
	}
}

func TestMetadataAndKeyRouting(t *testing.T) {
	broker := startFakeBroker(t, 3)
	conn := dial(t, broker)

	topic, err := conn.Topic(context.Background(), "orders")
	if err != nil {
		t.Fatalf("topic: %v", err)
	}
	if len(topic.Partitions) != 3 {
		t.Fatalf("got %d partitions, broker has 3", len(topic.Partitions))
	}

	// Routing must agree with the standalone function, and both with the vectors — the handle
	// indexes into the partition list rather than using the fold directly, and an off-by-one there
	// would be invisible with partitions numbered 0..n-1 only if the list happened to be sorted.
	key := []byte("user-1")
	if got, want := topic.PartitionFor(key), int32(PartitionFor(key, 3)); got != want {
		t.Fatalf("handle routed to %d, PartitionFor says %d", got, want)
	}

	// The same key, every time. This is what makes asking-then-sending safe with a key and unsafe
	// without one.
	for range 5 {
		if got := topic.PartitionFor(key); got != topic.PartitionFor(key) {
			t.Fatal("keyed routing is not a pure function of the key")
		}
	}
}

func TestUnkeyedRoutingAdvancesRoundRobin(t *testing.T) {
	broker := startFakeBroker(t, 3)
	conn := dial(t, broker)

	topic, err := conn.Topic(context.Background(), "orders")
	if err != nil {
		t.Fatalf("topic: %v", err)
	}

	seen := map[int32]int{}
	for range 9 {
		seen[topic.PartitionFor(nil)]++
	}
	if len(seen) != 3 {
		t.Fatalf("nine unkeyed records touched %d partitions of 3: %v", len(seen), seen)
	}
	for partition, count := range seen {
		if count != 3 {
			t.Errorf("partition %d took %d of nine, expected an even three", partition, count)
		}
	}
}

func TestTruncatedResponseIsAnErrorNotAPanic(t *testing.T) {
	// The decoder records the first shortfall instead of indexing past the end. A response cut
	// short by a broker restart is a connection error, and a client that panicked on it would take
	// the caller's process down for somebody else's crash.
	if _, err := decodeMetadata([]byte{0, 0, 0, 1, 0, 5}); err == nil {
		t.Fatal("a truncated METADATA body decoded without complaint")
	}
}

func allBytes() []byte {
	out := make([]byte, 256)
	for i := range out {
		out[i] = byte(i)
	}
	return out
}
