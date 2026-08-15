// Command probe measures what the long fetch is worth to a Go consumer, against a real broker.
//
// The conformance kit exercises FETCH with no wait at all, because its checks are about what one
// response contains. That leaves the client's own default — MaxWait of five seconds — verified by
// nothing, and it is the field that decides whether a caught-up consumer costs the broker a request
// per round trip or a request per five seconds. It is also v2-only, so a mistake in those four
// bytes would show up here and nowhere else.
//
// Three numbers, and the third is the one that makes the first two safe:
//
//   - **polling**, MaxWait=0: how many requests a caught-up consumer makes per second;
//   - **long fetch**, MaxWait=5s: the same consumer, same log, nothing to read;
//   - **wake-up**: how long after a record is written the waiting consumer has it. If the long
//     fetch bought its request rate by making new records wait for a timer, it would be a loss
//     dressed as a saving.
//
// Usage:  BOOBLIK_BROKER=host:port probe [topic]
package main

import (
	"context"
	"fmt"
	"os"
	"time"

	booblik "github.com/youndie/booblik/clients/go"
)

const (
	partition = int32(0)
	// Long enough that the polling column is not measuring one round trip, short enough that the
	// whole probe stays under half a minute.
	window = 3 * time.Second
)

func main() {
	address := os.Getenv("BOOBLIK_BROKER")
	if address == "" {
		fmt.Fprintln(os.Stderr, "BOOBLIK_BROKER is not set (host:port)")
		os.Exit(2)
	}
	topic := "probe"
	if len(os.Args) > 1 {
		topic = os.Args[1]
	}

	ctx := context.Background()
	conn, err := booblik.Dial(ctx, address)
	if err != nil {
		fail(err)
	}
	defer func() { _ = conn.Close() }()

	end, err := highWatermark(ctx, conn, topic)
	if err != nil {
		fail(err)
	}

	fmt.Printf("→ long fetch on a caught-up consumer — %s, partition %d, offset %d\n", topic, partition, end)
	fmt.Println()

	polling := requestsPerSecond(ctx, conn, topic, end, 0)
	waiting := requestsPerSecond(ctx, conn, topic, end, 5*time.Second)
	fmt.Printf("   polling (MaxWait=0)     %8.2f requests/s\n", polling)
	fmt.Printf("   long fetch (MaxWait=5s) %8.2f requests/s   %.0f× fewer\n", waiting, polling/max(waiting, 1e-9))

	delay, err := wakeUp(ctx, conn, address, topic)
	if err != nil {
		fail(err)
	}
	fmt.Printf("   wake-up on a new record %8.2f ms\n", float64(delay.Microseconds())/1000)
}

// requestsPerSecond counts fetches a consumer with nothing to read gets through in the window.
func requestsPerSecond(
	ctx context.Context,
	conn *booblik.Conn,
	topic string,
	offset int64,
	wait time.Duration,
) float64 {
	consumer := conn.Consumer(topic, partition, offset)
	consumer.MaxWait = wait

	started := time.Now()
	deadline := started.Add(window)
	requests := 0
	for time.Now().Before(deadline) {
		if _, err := consumer.Poll(ctx); err != nil {
			fail(err)
		}
		requests++
	}
	return float64(requests) / time.Since(started).Seconds()
}

// wakeUp measures the gap between a record being acknowledged and a waiting consumer having it.
//
// Two connections, and that is not incidental: the consumer is blocked inside a fetch that the
// broker is holding, and a second request on the same connection would queue behind the response
// it is waiting for. Which is also what a program with one connection and both roles would do.
func wakeUp(ctx context.Context, conn *booblik.Conn, address, topic string) (time.Duration, error) {
	end, err := highWatermark(ctx, conn, topic)
	if err != nil {
		return 0, err
	}

	consumer := conn.Consumer(topic, partition, end)
	consumer.MaxWait = 5 * time.Second

	arrived := make(chan time.Time, 1)
	failed := make(chan error, 1)
	go func() {
		records, err := consumer.Poll(ctx)
		if err != nil {
			failed <- err
			return
		}
		if len(records) == 0 {
			failed <- fmt.Errorf("the long fetch returned nothing: it timed out before the record landed")
			return
		}
		arrived <- time.Now()
	}()

	// Let the fetch reach the broker and start waiting. Writing first would measure a fetch that
	// already had something to answer with, which is the case this probe is not about.
	time.Sleep(200 * time.Millisecond)

	writer, err := booblik.Dial(ctx, address)
	if err != nil {
		return 0, err
	}
	defer func() { _ = writer.Close() }()

	// Stamped **before** the produce request goes out, not after it is acknowledged: what a reader
	// of this number wants is publish-to-receive, and stopping the clock at the write's own ack
	// would hide half of that behind a number that looks like the consumer's.
	written := time.Now()
	if _, err := writer.Produce(ctx, topic, partition, [][]byte{[]byte("wake")}, booblik.AckWritten); err != nil {
		return 0, err
	}

	select {
	case at := <-arrived:
		return at.Sub(written), nil
	case err := <-failed:
		return 0, err
	case <-time.After(10 * time.Second):
		return 0, fmt.Errorf("the consumer never woke up")
	}
}

func highWatermark(ctx context.Context, conn *booblik.Conn, topic string) (int64, error) {
	answer, err := conn.Metadata(ctx, topic)
	if err != nil {
		return 0, err
	}
	for _, info := range answer[topic] {
		if info.Partition == partition {
			return info.HighWatermark, nil
		}
	}
	return 0, fmt.Errorf("broker has no partition %d of %q", partition, topic)
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, err)
	os.Exit(1)
}
