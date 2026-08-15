package ru.workinprogress.booblik.java;

import java.util.ArrayList;
import java.util.List;

/**
 * This client under test, driven by {@code conformance/harness}.
 *
 * <p>The contract is in {@code conformance/README.md}: verbs on argv, {@code key=value} on stdout,
 * the broker in {@code BOOBLIK_BROKER}. Exit 0 means the verb was carried out — <b>including</b>
 * when the broker refused it, which is a result and is reported as {@code error=CODE}. A non-zero
 * exit means this program failed.
 *
 * <p>Declares {@code producer,consumer}.
 */
public final class Conformance {

    private Conformance() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: conformance <verb> [args...]  (broker in BOOBLIK_BROKER)");
            System.exit(2);
        }

        if ("capabilities".equals(args[0])) {
            System.out.println("roles=producer,consumer");
            System.out.println("name=java");
            return;
        }

        String address = System.getenv("BOOBLIK_BROKER");
        if (address == null || address.isEmpty()) {
            System.err.println("BOOBLIK_BROKER is not set (host:port)");
            System.exit(2);
        }

        try (Connection connection = Connection.open(address, 15_000)) {
            switch (args[0]) {
                case "metadata" -> metadata(connection, args[1]);
                case "produce" -> produce(connection, args[1], Integer.parseInt(args[2]), args[3], args[4]);
                case "produce-keyed" -> produceKeyed(connection, args[1], args[2], args[3]);
                case "fetch" -> fetch(
                        connection,
                        args[1],
                        Integer.parseInt(args[2]),
                        Long.parseLong(args[3]),
                        Integer.parseInt(args[4]));
                default -> {
                    System.err.println("unknown verb: " + args[0]);
                    System.exit(2);
                }
            }
        } catch (BrokerException refusal) {
            // A refusal is a result, not a failure of this program: report it and exit zero.
            System.out.println("error=" + refusal.code());
        }
    }

    private static void metadata(Connection connection, String topic) {
        List<PartitionInfo> partitions = connection.metadata(topic).get(topic);
        if (partitions == null) {
            return;
        }
        for (PartitionInfo info : partitions) {
            System.out.println(
                    "partition=" + info.partition() + " " + info.logStartOffset() + " " + info.highWatermark());
        }
    }

    private static void produce(
            Connection connection, String topic, int partition, String ack, String records) {

        AckPolicy policy =
                switch (ack) {
                    case "none" -> AckPolicy.NONE;
                    case "written" -> AckPolicy.WRITTEN;
                    case "forced" -> AckPolicy.FORCED;
                    default -> throw new IllegalArgumentException("unknown ack policy " + ack);
                };

        // An empty field is a zero-length record and it is passed on rather than tidied away: the
        // broker refuses those, and the harness checks that the refusal reaches the caller.
        List<byte[]> payloads = new ArrayList<>();
        for (String field : records.split(",", -1)) {
            payloads.add(decodeHex(field));
        }

        ProduceResult result = connection.produce(topic, partition, payloads, policy);
        // Null under AckPolicy.NONE, and printing nothing is the correct answer: no offset exists
        // yet. Reading for one here is what the harness times out on.
        if (result != null) {
            System.out.println("baseOffset=" + result.baseOffset());
            System.out.println("logEndOffset=" + result.logEndOffset());
        }
    }

    /**
     * Where the partitioner is exercised for real: the partition is chosen here, from the key,
     * because the broker never sees the key at all.
     */
    private static void produceKeyed(Connection connection, String name, String keyHex, String payloadHex) {
        byte[] key = decodeHex(keyHex);
        Connection.Topic topic = connection.topic(name);
        int chosen = topic.partitionFor(key);

        ProduceResult result =
                connection.produce(name, chosen, List.of(decodeHex(payloadHex)), AckPolicy.WRITTEN);

        System.out.println("partition=" + chosen);
        System.out.println("baseOffset=" + result.baseOffset());
    }

    /**
     * The raw call and not a {@link Consumer}: the checks are about what one FETCH answers — a
     * truncated tail, an empty response at the high watermark, a refusal past the end — and a
     * Consumer would smooth over exactly those by advancing a position and waiting.
     */
    private static void fetch(
            Connection connection, String topic, int partition, long offset, int maxBytes) {
        Fetched answer = connection.fetch(topic, partition, offset, maxBytes, 0, 0);

        System.out.println("highWatermark=" + answer.highWatermark());

        // Nothing whole and something partial: the next record is bigger than maxBytes and never
        // arrives, so a caller that only sees an empty list cannot tell this from having caught up.
        if (answer.records().isEmpty() && answer.truncated()) {
            System.out.println("recordExceedsMaxBytes=" + answer.truncatedRecordBytes());
            return;
        }

        for (byte[] record : answer.records()) {
            System.out.println("record=" + java.util.HexFormat.of().formatHex(record));
        }
    }

    private static byte[] decodeHex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int index = 0; index < out.length; index++) {
            out[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
        }
        return out;
    }
}
