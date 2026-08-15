package ru.workinprogress.booblik.java;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One connection to a broker.
 *
 * <p><b>Not safe for concurrent use.</b> Requests and responses are matched by a correlation id in
 * the order they were sent, so two threads sharing a Connection would read each other's answers.
 * Use one per thread, or a {@link Producer}, which owns its own.
 *
 * <p>Failures are unchecked. A refusal by the broker is a {@link BrokerException} and leaves the
 * connection usable; bytes that do not make sense are a {@link ProtocolException}; a socket that
 * dropped is an {@link UncheckedIOException}. Checked exceptions would put an {@code IOException}
 * on every call for a case most callers handle in one place if at all.
 */
public final class Connection implements AutoCloseable {

    private static final short API_PRODUCE = 1;
    private static final short API_FETCH = 2;
    private static final short API_METADATA = 3;
    private static final short VERSION = 1;

    /** FETCH alone has a v2, which adds maxWaitMillis and minBytes. The other two have no v2. */
    private static final short FETCH_VERSION = 2;

    /**
     * payloadSize and crc32c, in front of every record inside a FETCH response — the on-disk format
     * unchanged, which is what lets the broker send segment bytes without touching them.
     */
    private static final int RECORD_HEADER_BYTES = 4 + 4;

    /** What the length prefix counts before the payload: apiKey, apiVersion, correlationId. */
    private static final int REQUEST_HEADER_BYTES = 2 + 2 + 4;

    /** correlationId and errorCode, on every response. */
    private static final int RESPONSE_HEADER_BYTES = 4 + 2;

    private static final int MAX_FRAME_BYTES = 8 << 20;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private int correlation;

    private Connection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    /**
     * @param address {@code host:port}, which is the form every configuration uses.
     * @param timeoutMillis how long to wait for the connection and for each read
     */
    public static Connection open(String address, int timeoutMillis) {
        int separator = address.lastIndexOf(':');
        if (separator <= 0) {
            throw new IllegalArgumentException("expected host:port, got '" + address + "'");
        }
        String host = address.substring(0, separator);
        int port = Integer.parseInt(address.substring(separator + 1));

        try {
            Socket socket = new Socket();
            // The requests are small and every one is a round trip, so Nagle's algorithm would add
            // a delayed acknowledgement to each.
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            return new Connection(socket);
        } catch (IOException failure) {
            throw new UncheckedIOException("booblik: cannot connect to " + address, failure);
        }
    }

    public static Connection open(String address) {
        return open(address, 30_000);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a socket that is already gone is not a failure worth reporting to a caller
            // who has said it is finished with it.
        }
    }

    // -- framing ---------------------------------------------------------------------------------

    private int send(short apiKey, short version, byte[] payload) {
        int correlationId = ++correlation;
        try {
            out.writeInt(REQUEST_HEADER_BYTES + payload.length);
            out.writeShort(apiKey);
            out.writeShort(version);
            out.writeInt(correlationId);
            out.write(payload);
            // Buffered until here, so a request is one write to the socket rather than five.
            out.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException("booblik: write request", failure);
        }
        return correlationId;
    }

    private byte[] receive(int expect) {
        try {
            int length = in.readInt();
            if (length < RESPONSE_HEADER_BYTES || length > MAX_FRAME_BYTES) {
                throw new ProtocolException("response frame length " + length + " is out of range");
            }

            byte[] frame = new byte[length];
            in.readFully(frame);

            Reader reader = new Reader(frame);
            int correlationId = reader.int32();
            Code code = Code.of(reader.int16());

            // Checked rather than trusted. A response carrying somebody else's correlation id does
            // not merely lose information — it **resolves the wrong request**, handing one caller
            // another caller's offsets.
            if (correlationId != expect) {
                throw new ProtocolException("response " + correlationId + " answered request " + expect);
            }
            if (code != Code.NONE) {
                throw new BrokerException(code);
            }
            return reader.rest();
        } catch (IOException failure) {
            throw new UncheckedIOException("booblik: read response", failure);
        }
    }

    // -- requests --------------------------------------------------------------------------------

    /**
     * Which topics exist and where each partition currently starts and ends. No names asks for
     * everything.
     *
     * <p>A named topic that does not exist fails the whole request with UNKNOWN_TOPIC_OR_PARTITION
     * rather than being left out of the answer — otherwise "no such topic" and "the topic is empty"
     * would arrive looking identical.
     */
    public Map<String, List<PartitionInfo>> metadata(String... topics) {
        Writer writer = new Writer();
        writer.int32(topics.length);
        for (String topic : topics) {
            writer.string(topic);
        }
        return decodeMetadata(receive(send(API_METADATA, VERSION, writer.bytes())));
    }

    /**
     * Appends records to one partition as a single request.
     *
     * <p>They land <b>contiguously</b> — one request is written by one call into that partition's
     * writer, so the offsets run from {@code baseOffset} with nothing interleaved. It is <b>not</b>
     * atomic: a crash mid-write leaves whatever reached the disk, and recovery keeps the prefix that
     * passes its checksums. There are no transactions in this broker.
     *
     * <p>Returns {@code null} under {@link AckPolicy#NONE}, because no answer is coming.
     *
     * <p>Nothing here validates record sizes. The broker refuses empty records and empty batches
     * with CORRUPT_REQUEST, and duplicating that rule client-side would create a second place to
     * disagree with it — the rule belongs to whatever has to store the result.
     */
    public ProduceResult produce(String topic, int partition, List<byte[]> records, AckPolicy ack) {
        Writer writer = new Writer();
        writer.string(topic);
        writer.int32(partition);
        writer.int8(ack.id());
        writer.int32(records.size());
        for (byte[] record : records) {
            writer.int32(record.length);
            writer.bytes(record);
        }

        int correlationId = send(API_PRODUCE, VERSION, writer.bytes());
        if (ack == AckPolicy.NONE) {
            return null;
        }

        Reader reader = new Reader(receive(correlationId));
        return new ProduceResult(reader.int64(), reader.int64());
    }

    /**
     * Reads records from one partition, checksum-verified.
     *
     * <p>{@code maxBytes} bounds the response <b>in bytes, not in records</b>, so it can stop inside
     * one; see {@link Fetched#truncated()}. {@code maxWaitMillis} is how long the broker may hold a
     * request that has nothing to answer with, and zero returns at once.
     *
     * <p>{@code minBytes} greater than {@code maxBytes} is CORRUPT_REQUEST: a request that can never
     * be satisfied. That is left to the broker rather than checked here, so there is one place that
     * decides it instead of two that can disagree.
     *
     * <p>Always v2, including when nothing is being waited for. One code path rather than two: a
     * client that switched versions depending on its arguments would exercise v1 only in the branch
     * nobody debugs. The broker still decodes v1 for anybody else's client.
     */
    public Fetched fetch(
            String topic, int partition, long offset, int maxBytes, int maxWaitMillis, int minBytes) {
        Writer writer = new Writer();
        writer.string(topic);
        writer.int32(partition);
        writer.int64(offset);
        writer.int32(maxBytes);
        writer.int32(maxWaitMillis);
        writer.int32(minBytes);

        return decodeFetch(receive(send(API_FETCH, FETCH_VERSION, writer.bytes())), offset);
    }

    /**
     * A {@link Consumer} reading this partition from {@code start}.
     *
     * <p>Reading "from the beginning" means starting at the partition's {@code logStartOffset} from
     * {@link #metadata}, not at zero: zero is OFFSET_OUT_OF_RANGE on any topic that has ever dropped
     * a segment to retention. Reading "only what is new" means its {@code highWatermark}.
     */
    public Consumer consumer(String topic, int partition, long start) {
        return new Consumer(this, topic, partition, start);
    }

    /**
     * Unframes a FETCH response and verifies every checksum.
     *
     * <p>{@code offset} is the one that was asked for, and it is here only so that a failure can say
     * <em>which</em> record is damaged rather than that one of them is.
     */
    static Fetched decodeFetch(byte[] body, long offset) {
        Reader reader = new Reader(body);
        long highWatermark = reader.int64();
        int promised = reader.int32();
        byte[] payload = reader.rest();

        // The frame length already bounds the payload, so this field is redundant — which is exactly
        // what makes it worth checking. It is computed before the transfer starts, while the bytes
        // arrive afterwards from `transferTo` in an unpredictable number of pieces; a disagreement
        // means the two halves of the response came from different states of the log.
        if (promised != payload.length) {
            throw new ProtocolException(
                    "FETCH promised " + promised + " payload bytes and the frame carries " + payload.length);
        }

        List<byte[]> records = new ArrayList<>();
        int cursor = 0;

        while (payload.length - cursor >= RECORD_HEADER_BYTES) {
            Reader header = new Reader(java.util.Arrays.copyOfRange(payload, cursor, cursor + RECORD_HEADER_BYTES));
            int size = header.int32();
            int stored = header.int32();

            // A whole header is either there or not — parsing always resumes on a record boundary —
            // so a non-positive size is a malformed frame rather than a truncated tail. Empty
            // records cannot be stored at all, which is why the broker refuses them.
            if (size <= 0) {
                throw new ProtocolException(
                        "record header at offset " + (offset + records.size()) + " says " + size + " bytes");
            }
            if (size > payload.length - cursor - RECORD_HEADER_BYTES) {
                return new Fetched(highWatermark, records, true, size);
            }

            int start = cursor + RECORD_HEADER_BYTES;
            byte[] record = java.util.Arrays.copyOfRange(payload, start, start + size);

            // After the length check and never before it: a truncated tail is not corruption, and
            // reporting it as such would turn the most ordinary response there is into an alarm.
            int computed = checksum(record);
            if (computed != stored) {
                throw new CorruptRecordException(offset + records.size(), stored, computed);
            }

            records.add(record);
            cursor = start + size;
        }

        // Fewer bytes left than a record header: the response stopped inside the header of the next
        // record, which is the same truncation with nothing to say about its size.
        return new Fetched(highWatermark, records, cursor < payload.length, 0);
    }

    /**
     * CRC-32C (Castagnoli), and the one place this client gets something for free that the others
     * hand-roll: {@code java.util.zip.CRC32C} has been in the JDK since 9, well under this client's
     * target of 17, and it compiles to the hardware instruction.
     *
     * <p><b>Not {@code java.util.zip.CRC32}</b>, one line away in the same package and a different
     * polynomial. Both are called "CRC32", both return a plausible number, and a client using the
     * wrong one rejects every record it reads. The check value for {@code 123456789} is
     * {@code 0xE3069283}; {@code CRC32} gives {@code 0xCBF43926}.
     */
    static int checksum(byte[] record) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(record, 0, record.length);
        // getValue() widens to long, and the sum is compared against a signed int off the wire — so
        // the cast is what keeps 0x82F63B78 from becoming 0x0000000082F63B78 and matching nothing.
        return (int) crc.getValue();
    }

    /**
     * A handle with the topic's partitions taken from the broker rather than from an argument.
     *
     * <p>Asking is the point. A partition count supplied by hand can disagree with the broker, and
     * what that produces — records piling into the partitions that exist while others are never
     * written to — reads as a data problem rather than the configuration mistake it is.
     */
    public Topic topic(String name) {
        List<PartitionInfo> infos = metadata(name).get(name);
        if (infos == null || infos.isEmpty()) {
            throw new ProtocolException("broker has no partitions for " + name);
        }
        int[] partitions = new int[infos.size()];
        for (int index = 0; index < partitions.length; index++) {
            partitions[index] = infos.get(index).partition();
        }
        return new Topic(this, name, partitions);
    }

    static Map<String, List<PartitionInfo>> decodeMetadata(byte[] body) {
        Reader reader = new Reader(body);
        int topicCount = reader.int32();
        Map<String, List<PartitionInfo>> result = new HashMap<>();

        for (int topic = 0; topic < topicCount; topic++) {
            String name = reader.string();
            int partitionCount = reader.int32();
            List<PartitionInfo> partitions = new ArrayList<>(partitionCount);
            for (int partition = 0; partition < partitionCount; partition++) {
                // Read into locals, in order: three fields off the same frame whose meaning is
                // positional, and a constructor call would leave which is which to argument
                // evaluation order.
                int id = reader.int32();
                long logStartOffset = reader.int64();
                long highWatermark = reader.int64();
                partitions.add(new PartitionInfo(id, logStartOffset, highWatermark));
            }
            result.put(name, partitions);
        }
        return result;
    }

    /** One topic, so its name and its routing stop being arguments to every call. */
    public static final class Topic {

        private final Connection connection;
        private final String name;
        private final int[] partitions;
        private final AtomicInteger roundRobin = new AtomicInteger();

        Topic(Connection connection, String name, int[] partitions) {
            this.connection = connection;
            this.name = name;
            this.partitions = partitions;
        }

        public String name() {
            return name;
        }

        public int[] partitions() {
            return partitions.clone();
        }

        /**
         * Where a record with this key goes.
         *
         * <p>A null key takes the next partition round-robin, and that counter advances on every
         * call — so asking and then sending is two turns of it and the records start skipping
         * partitions. With a key there is no such thing: the answer is a pure function of the key.
         */
        public int partitionFor(byte[] key) {
            if (key == null) {
                return partitions[Math.floorMod(roundRobin.getAndIncrement(), partitions.length)];
            }
            return partitions[Partitioner.partitionFor(key, partitions.length)];
        }

        /**
         * Publishes one record, choosing its partition from {@code key}.
         *
         * <p>One record per request throws away the largest single performance factor there is: the
         * broker's own measurements put batches of a hundred at 4 335 482 records/s against 80 592
         * one at a time. Use {@link Connection#produce} with a list, or a {@link Producer}, whenever
         * records are available together.
         */
        public ProduceResult send(byte[] record, byte[] key, AckPolicy ack) {
            return connection.produce(name, partitionFor(key), List.of(record), ack);
        }
    }

    /** Big-endian writes into a growable buffer. */
    private static final class Writer {

        private byte[] buffer = new byte[64];
        private int at;

        void int8(byte value) {
            ensure(1);
            buffer[at++] = value;
        }

        void int64(long value) {
            int32((int) (value >>> 32));
            int32((int) value);
        }

        void int32(int value) {
            ensure(4);
            buffer[at++] = (byte) (value >>> 24);
            buffer[at++] = (byte) (value >>> 16);
            buffer[at++] = (byte) (value >>> 8);
            buffer[at++] = (byte) value;
        }

        void string(String value) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            ensure(2 + encoded.length);
            buffer[at++] = (byte) (encoded.length >>> 8);
            buffer[at++] = (byte) encoded.length;
            bytes(encoded);
        }

        void bytes(byte[] source) {
            ensure(source.length);
            System.arraycopy(source, 0, buffer, at, source.length);
            at += source.length;
        }

        byte[] bytes() {
            return java.util.Arrays.copyOf(buffer, at);
        }

        private void ensure(int more) {
            if (at + more > buffer.length) {
                buffer = java.util.Arrays.copyOf(buffer, Math.max(buffer.length * 2, at + more));
            }
        }
    }

    /** Big-endian reads, refusing to run past the end. */
    private static final class Reader {

        private final byte[] buffer;
        private int at;

        Reader(byte[] buffer) {
            this.buffer = buffer;
        }

        short int16() {
            require(2);
            int value = ((buffer[at] & 0xFF) << 8) | (buffer[at + 1] & 0xFF);
            at += 2;
            return (short) value;
        }

        int int32() {
            require(4);
            int value =
                    ((buffer[at] & 0xFF) << 24)
                            | ((buffer[at + 1] & 0xFF) << 16)
                            | ((buffer[at + 2] & 0xFF) << 8)
                            | (buffer[at + 3] & 0xFF);
            at += 4;
            return value;
        }

        long int64() {
            long high = Integer.toUnsignedLong(int32());
            long low = Integer.toUnsignedLong(int32());
            return (high << 32) | low;
        }

        String string() {
            int length = int16() & 0xFFFF;
            require(length);
            String value = new String(buffer, at, length, StandardCharsets.UTF_8);
            at += length;
            return value;
        }

        byte[] rest() {
            return java.util.Arrays.copyOfRange(buffer, at, buffer.length);
        }

        private void require(int count) {
            if (buffer.length - at < count) {
                throw new ProtocolException(
                        "frame ends after " + (buffer.length - at) + " bytes, needed " + count + " more");
            }
        }
    }
}
