// Command conformance is this client under test, driven by conformance/harness.
//
// The contract is in conformance/README.md: verbs on argv, `key=value` on stdout, the broker in
// BOOBLIK_BROKER. Exit 0 means the verb was carried out — **including** when the broker refused it,
// which is a result and is reported as `error=CODE`. A non-zero exit means this program failed.
//
// It declares both roles. `consumer` was added in M-136, when this became the first client that can
// read: the four consumer checks had been written in M-131 and reported as not applicable by every
// client in the kit until something answered `fetch`.
package main

import (
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	booblik "github.com/youndie/booblik/clients/go"
)

func main() {
	if len(os.Args) < 2 {
		fail(2, "usage: conformance <verb> [args...]  (broker in BOOBLIK_BROKER)")
	}
	verb := os.Args[1]

	if verb == "capabilities" {
		fmt.Println("roles=producer,consumer")
		fmt.Println("name=go")
		return
	}

	address := os.Getenv("BOOBLIK_BROKER")
	if address == "" {
		fail(2, "BOOBLIK_BROKER is not set (host:port)")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	conn, err := booblik.Dial(ctx, address)
	if err != nil {
		fail(1, "%v", err)
	}
	defer func() { _ = conn.Close() }()

	switch verb {
	case "metadata":
		err = metadata(ctx, conn, os.Args[2])
	case "produce":
		err = produce(ctx, conn, os.Args[2], os.Args[3], os.Args[4], os.Args[5])
	case "produce-keyed":
		err = produceKeyed(ctx, conn, os.Args[2], os.Args[3], os.Args[4])
	case "fetch":
		err = fetch(ctx, conn, os.Args[2], os.Args[3], os.Args[4], os.Args[5])
	default:
		fail(2, "unknown verb: %s", verb)
	}

	// A refusal is a result, not a failure of this program: it is reported and the exit stays zero.
	// Anything else means the client itself broke, which is never an expected outcome of a check.
	var refusal *booblik.BrokerError
	switch {
	case errors.As(err, &refusal):
		fmt.Printf("error=%s\n", refusal.Code)
	case err != nil:
		fail(1, "%v", err)
	}
}

func metadata(ctx context.Context, conn *booblik.Conn, topic string) error {
	answer, err := conn.Metadata(ctx, topic)
	if err != nil {
		return err
	}
	for _, info := range answer[topic] {
		fmt.Printf("partition=%d %d %d\n", info.Partition, info.LogStartOffset, info.HighWatermark)
	}
	return nil
}

func produce(ctx context.Context, conn *booblik.Conn, topic, partition, ack, records string) error {
	number, err := strconv.Atoi(partition)
	if err != nil {
		return fmt.Errorf("partition %q: %w", partition, err)
	}
	policy, err := ackPolicy(ack)
	if err != nil {
		return err
	}

	payloads, err := decodeRecords(records)
	if err != nil {
		return err
	}

	result, err := conn.Produce(ctx, topic, int32(number), payloads, policy)
	if err != nil {
		return err
	}
	// Nil under AckNone, and printing nothing is the correct answer: no offset exists yet. Reading
	// for one here is what the harness times out on.
	if result != nil {
		fmt.Printf("baseOffset=%d\n", result.BaseOffset)
		fmt.Printf("logEndOffset=%d\n", result.LogEndOffset)
	}
	return nil
}

// produceKeyed is where the partitioner is exercised for real: the partition is chosen here, from
// the key, because the broker never sees the key at all.
func produceKeyed(ctx context.Context, conn *booblik.Conn, topicName, keyHex, payloadHex string) error {
	key, err := hex.DecodeString(keyHex)
	if err != nil {
		return fmt.Errorf("key %q: %w", keyHex, err)
	}
	payload, err := hex.DecodeString(payloadHex)
	if err != nil {
		return fmt.Errorf("payload %q: %w", payloadHex, err)
	}

	// Partitions come from METADATA rather than from a count supplied by hand — a count that
	// disagrees with the broker produces records piling into the partitions that exist, which reads
	// as a data problem rather than the configuration mistake it is.
	topic, err := conn.Topic(ctx, topicName)
	if err != nil {
		return err
	}

	chosen := topic.PartitionFor(key)
	result, err := conn.Produce(ctx, topicName, chosen, [][]byte{payload}, booblik.AckWritten)
	if err != nil {
		return err
	}

	fmt.Printf("partition=%d\n", chosen)
	fmt.Printf("baseOffset=%d\n", result.BaseOffset)
	return nil
}

// fetch reads one response and prints what came back, without a Consumer around it.
//
// Deliberately the raw call: the checks are about what one FETCH answers — a truncated tail, an
// empty response at the high watermark, a refusal past the end — and a Consumer would smooth over
// exactly those by advancing a position and waiting. It also asks for no wait at all, so the check
// that reads at the high watermark answers immediately instead of holding the default five seconds
// for records nobody is going to write.
func fetch(ctx context.Context, conn *booblik.Conn, topic, partition, offset, maxBytes string) error {
	number, err := strconv.Atoi(partition)
	if err != nil {
		return fmt.Errorf("partition %q: %w", partition, err)
	}
	from, err := strconv.ParseInt(offset, 10, 64)
	if err != nil {
		return fmt.Errorf("offset %q: %w", offset, err)
	}
	limit, err := strconv.Atoi(maxBytes)
	if err != nil {
		return fmt.Errorf("maxBytes %q: %w", maxBytes, err)
	}

	answer, err := conn.Fetch(ctx, booblik.FetchRequest{
		Topic:     topic,
		Partition: int32(number),
		Offset:    from,
		MaxBytes:  int32(limit),
	})
	if err != nil {
		return err
	}

	fmt.Printf("highWatermark=%d\n", answer.HighWatermark)
	// Nothing whole and something partial: the next record is bigger than maxBytes and never
	// arrives, so a caller that only sees an empty list cannot tell this from having caught up.
	if len(answer.Records) == 0 && answer.Truncated {
		fmt.Printf("recordExceedsMaxBytes=%d\n", answer.TruncatedRecordBytes)
		return nil
	}
	for _, record := range answer.Records {
		fmt.Printf("record=%s\n", hex.EncodeToString(record))
	}
	return nil
}

func ackPolicy(name string) (booblik.AckPolicy, error) {
	switch name {
	case "none":
		return booblik.AckNone, nil
	case "written":
		return booblik.AckWritten, nil
	case "forced":
		return booblik.AckForced, nil
	default:
		return 0, fmt.Errorf("unknown ack policy %q", name)
	}
}

// decodeRecords splits comma-separated hex. An empty field is a zero-length record, and it is
// passed on rather than tidied away: the broker refuses those, and the harness checks that the
// refusal reaches the caller instead of being swallowed here.
func decodeRecords(argument string) ([][]byte, error) {
	fields := strings.Split(argument, ",")
	records := make([][]byte, 0, len(fields))
	for _, field := range fields {
		payload, err := hex.DecodeString(field)
		if err != nil {
			return nil, fmt.Errorf("record %q: %w", field, err)
		}
		records = append(records, payload)
	}
	return records, nil
}

func fail(code int, format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(code)
}
