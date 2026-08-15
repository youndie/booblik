package ru.workinprogress.booblik.java;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A broker for tests: speaks the protocol well enough to answer, and <b>decodes what the client
 * encoded</b> rather than pattern-matching bytes.
 *
 * <p>That is the point of it. An encoding mistake becomes a decode failure here instead of a mystery
 * against a real broker, and the test suite needs no Docker, no network and no fixtures. It is also
 * a separate reading of {@code docs/api/protocol-wire.md} from the client it checks.
 */
final class FakeBroker implements AutoCloseable {

    private static final short API_PRODUCE = 1;
    private static final short API_FETCH = 2;
    private static final short API_METADATA = 3;

    /**
     * Flips a bit in every stored checksum, which is what a damaged segment looks like from the
     * socket: the bytes arrive, and only the sum disagrees with them.
     */
    volatile boolean corrupt;

    /** The apiVersion of the last request, so a test can assert what was actually sent. */
    volatile short lastVersion;

    /** This fixture's own decoding of the last FETCH frame. */
    volatile int[] lastFetch;

    private final ServerSocket listener;
    private final Object gate = new Object();
    private final Map<String, List<byte[]>> produced = new HashMap<>();
    private final int partitions;

    private long nextOffset;
    private int requests;
    private Code refuseWith = Code.NONE;

    FakeBroker(int partitions) {
        this.partitions = partitions;
        try {
            this.listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        Thread accepting = new Thread(this::accept, "fake-broker");
        accepting.setDaemon(true);
        accepting.start();
    }

    String address() {
        return "127.0.0.1:" + listener.getLocalPort();
    }

    void refuse(Code code) {
        synchronized (gate) {
            refuseWith = code;
        }
    }

    int requests() {
        synchronized (gate) {
            return requests;
        }
    }

    List<byte[]> recordsIn(String topic, int partition) {
        synchronized (gate) {
            return new ArrayList<>(produced.getOrDefault(topic + " " + partition, List.of()));
        }
    }

    @Override
    public void close() {
        try {
            listener.close();
        } catch (IOException ignored) {
            // Nothing a test can do about a listener that is already gone.
        }
    }

    private void accept() {
        while (!listener.isClosed()) {
            try {
                Socket connection = listener.accept();
                Thread serving = new Thread(() -> serve(connection), "fake-broker-connection");
                serving.setDaemon(true);
                serving.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket connection) {
        try (connection;
                DataInputStream in = new DataInputStream(connection.getInputStream());
                DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {

            while (true) {
                int length = in.readInt();
                byte[] frame = new byte[length];
                in.readFully(frame);

                short apiKey = (short) (((frame[0] & 0xFF) << 8) | (frame[1] & 0xFF));
                lastVersion = (short) (((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF));
                int correlation =
                        ((frame[4] & 0xFF) << 24)
                                | ((frame[5] & 0xFF) << 16)
                                | ((frame[6] & 0xFF) << 8)
                                | (frame[7] & 0xFF);
                byte[] payload = java.util.Arrays.copyOfRange(frame, 8, frame.length);

                byte[] body;
                if (apiKey == API_PRODUCE) {
                    Produced answer = produce(payload);
                    if (answer.silent()) {
                        continue;
                    }
                    body = answer.body();
                } else if (apiKey == API_FETCH) {
                    body = fetch(payload);
                } else if (apiKey == API_METADATA) {
                    body = metadata(payload);
                } else {
                    body = new byte[0];
                }

                Code refusal;
                synchronized (gate) {
                    refusal = refuseWith;
                }
                out.writeInt(6 + body.length);
                out.writeInt(correlation);
                out.writeShort(refusal.id());
                out.write(body);
                out.flush();
            }
        } catch (IOException finished) {
            // The client hung up, which is how every one of these connections ends.
        }
    }

    private record Produced(byte[] body, boolean silent) {}

    /**
     * Silence is what {@link AckPolicy#NONE} means on the wire, and the behaviour a client most
     * often gets wrong.
     */
    private Produced produce(byte[] payload) {
        Cursor cursor = new Cursor(payload);
        String topic = cursor.string();
        int partition = cursor.int32();
        AckPolicy ack = AckPolicy.values()[cursor.int8()];
        int count = cursor.int32();

        List<byte[]> records = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            records.add(cursor.bytes(cursor.int32()));
        }

        long base;
        synchronized (gate) {
            base = nextOffset;
            if (refuseWith == Code.NONE) {
                produced.computeIfAbsent(topic + " " + partition, ignored -> new ArrayList<>()).addAll(records);
                nextOffset += records.size();
            }
            requests++;
        }

        if (ack == AckPolicy.NONE) {
            return new Produced(new byte[0], true);
        }
        byte[] body = new byte[16];
        writeLong(body, 0, base);
        writeLong(body, 8, base + records.size());
        return new Produced(body, false);
    }

    /**
     * Puts records in a partition's log without going through PRODUCE, so a fetch test states what is
     * there to read instead of arranging for it. Offsets in this fixture are indices into that list,
     * per partition.
     */
    void seed(String topic, int partition, byte[]... records) {
        synchronized (gate) {
            produced.computeIfAbsent(topic + " " + partition, ignored -> new ArrayList<>())
                    .addAll(List.of(records));
        }
    }

    /**
     * Answers from the seeded log, framing records exactly as the disk holds them — payloadSize,
     * crc32c, payload — and cutting the response at maxBytes <b>in bytes</b>, which is what puts a
     * partial record at the end of a full response.
     *
     * <p>maxWait is decoded and remembered, then ignored: nothing here can produce a record while a
     * request waits, so holding one would only make the tests slower.
     */
    private byte[] fetch(byte[] payload) {
        Cursor cursor = new Cursor(payload);
        String topic = cursor.string();
        int partition = cursor.int32();
        long offset = ((long) cursor.int32() << 32) | (cursor.int32() & 0xFFFFFFFFL);
        int maxBytes = cursor.int32();
        lastFetch = new int[] {partition, (int) offset, maxBytes, cursor.int32(), cursor.int32()};

        List<byte[]> log = recordsIn(topic, partition);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try {
            DataOutputStream framing = new DataOutputStream(stream);
            for (byte[] record : log.subList((int) Math.min(offset, log.size()), log.size())) {
                java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
                crc.update(record, 0, record.length);
                framing.writeInt(record.length);
                framing.writeInt((int) crc.getValue() ^ (corrupt ? 1 : 0));
                framing.write(record);
            }
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }

        byte[] all = stream.toByteArray();
        byte[] cut = java.util.Arrays.copyOf(all, Math.min(all.length, maxBytes));

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream(body);
            out.writeLong(log.size());
            out.writeInt(cut.length);
            out.write(cut);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return body.toByteArray();
    }

    private byte[] metadata(byte[] payload) {
        Cursor cursor = new Cursor(payload);
        int count = cursor.int32();
        List<String> names = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            names.add(cursor.string());
        }
        if (names.isEmpty()) {
            names.add("everything");
        }

        long highWatermark;
        synchronized (gate) {
            highWatermark = nextOffset;
        }

        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        try (DataOutputStream writer = new DataOutputStream(body)) {
            writer.writeInt(names.size());
            for (String name : names) {
                byte[] encoded = name.getBytes(StandardCharsets.UTF_8);
                writer.writeShort(encoded.length);
                writer.write(encoded);
                writer.writeInt(partitions);
                for (int partition = 0; partition < partitions; partition++) {
                    writer.writeInt(partition);
                    writer.writeLong(0);
                    writer.writeLong(highWatermark);
                }
            }
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return body.toByteArray();
    }

    private static void writeLong(byte[] target, int at, long value) {
        for (int index = 0; index < 8; index++) {
            target[at + index] = (byte) (value >>> (56 - 8 * index));
        }
    }

    /** Decodes what the client encoded — deliberately by hand, not with the client's own reader. */
    private static final class Cursor {

        private final byte[] buffer;
        private int at;

        Cursor(byte[] buffer) {
            this.buffer = buffer;
        }

        int int8() {
            return buffer[at++] & 0xFF;
        }

        int int32() {
            int value =
                    ((buffer[at] & 0xFF) << 24)
                            | ((buffer[at + 1] & 0xFF) << 16)
                            | ((buffer[at + 2] & 0xFF) << 8)
                            | (buffer[at + 3] & 0xFF);
            at += 4;
            return value;
        }

        String string() {
            int length = ((buffer[at] & 0xFF) << 8) | (buffer[at + 1] & 0xFF);
            at += 2;
            String value = new String(buffer, at, length, StandardCharsets.UTF_8);
            at += length;
            return value;
        }

        byte[] bytes(int count) {
            byte[] slice = java.util.Arrays.copyOfRange(buffer, at, at + count);
            at += count;
            return slice;
        }
    }
}
