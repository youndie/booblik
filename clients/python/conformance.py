"""This client under test, driven by conformance/harness.

The contract is in conformance/README.md: verbs on argv, ``key=value`` on stdout, the broker in
BOOBLIK_BROKER. Exit 0 means the verb was carried out — **including** when the broker refused it,
which is a result and is reported as ``error=CODE``. A non-zero exit means this program failed.

Declares ``producer,consumer``: the second implementation to read, after Go, which is what turns
the four consumer checks from "something answers them" into a specification two independent readings
agree on.
"""

import os
import sys

from booblik import AckPolicy, BrokerError, Connection

ACK = {"none": AckPolicy.NONE, "written": AckPolicy.WRITTEN, "forced": AckPolicy.FORCED}


def main(argv: list[str]) -> int:
    if not argv:
        print("usage: conformance.py <verb> [args...]  (broker in BOOBLIK_BROKER)", file=sys.stderr)
        return 2

    verb, args = argv[0], argv[1:]

    if verb == "capabilities":
        print("roles=producer,consumer")
        print("name=python")
        return 0

    address = os.environ.get("BOOBLIK_BROKER")
    if not address:
        print("BOOBLIK_BROKER is not set (host:port)", file=sys.stderr)
        return 2

    with Connection.connect(address, timeout=15) as connection:
        try:
            if verb == "metadata":
                metadata(connection, args[0])
            elif verb == "produce":
                produce(connection, args[0], int(args[1]), args[2], args[3])
            elif verb == "produce-keyed":
                produce_keyed(connection, args[0], bytes.fromhex(args[1]), bytes.fromhex(args[2]))
            elif verb == "fetch":
                fetch(connection, args[0], int(args[1]), int(args[2]), int(args[3]))
            else:
                print(f"unknown verb: {verb}", file=sys.stderr)
                return 2
        except BrokerError as refusal:
            # A refusal is a result, not a failure of this program: report it and exit zero.
            print(f"error={refusal.code.name}")
    return 0


def metadata(connection: Connection, topic: str) -> None:
    for info in connection.metadata(topic).get(topic, []):
        print(f"partition={info.partition} {info.log_start_offset} {info.high_watermark}")


def produce(connection: Connection, topic: str, partition: int, ack: str, records: str) -> None:
    # An empty field is a zero-length record and it is passed on rather than tidied away: the broker
    # refuses those, and the harness checks that the refusal reaches the caller.
    payloads = [bytes.fromhex(field) for field in records.split(",")]

    result = connection.produce(topic, partition, payloads, ACK[ack])
    # None under AckPolicy.NONE, and printing nothing is the correct answer: no offset exists yet.
    # Reading for one here is what the harness times out on.
    if result is not None:
        print(f"baseOffset={result.base_offset}")
        print(f"logEndOffset={result.log_end_offset}")


def produce_keyed(connection: Connection, name: str, key: bytes, payload: bytes) -> None:
    """Where the partitioner is exercised for real: the partition is chosen here, from the key,
    because the broker never sees the key at all."""
    topic = connection.topic(name)
    chosen = topic.partition_for(key)

    result = connection.produce(name, chosen, [payload])
    print(f"partition={chosen}")
    print(f"baseOffset={result.base_offset}")


def fetch(connection: Connection, topic: str, partition: int, offset: int, max_bytes: int) -> None:
    """One response, printed as it came — deliberately without a Consumer around it.

    The checks are about what a single FETCH answers: a truncated tail, an empty response at the
    high watermark, a refusal past the end. A Consumer would smooth over exactly those by advancing
    a position and waiting.
    """
    answer = connection.fetch(topic, partition, offset, max_bytes)
    print(f"highWatermark={answer.high_watermark}")

    # Nothing whole and something partial: the next record is bigger than max_bytes and never
    # arrives, so a caller that only sees an empty list cannot tell this from having caught up.
    if not answer.records and answer.truncated:
        print(f"recordExceedsMaxBytes={answer.truncated_record_bytes}")
        return

    for record in answer.records:
        print(f"record={record.hex()}")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
