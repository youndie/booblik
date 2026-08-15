"""The asyncio client under test, driven by conformance/harness.

A second entry point rather than a flag on ``conformance.py``: the two share the codec and share
nothing else, and a branch in the middle of a conformance client is a way to check one path twice
while believing you checked both.

The contract is in ``conformance/README.md``. Declares ``producer,consumer``.
"""

import asyncio
import os
import sys

from booblik.aio import AckPolicy, Connection
from booblik.errors import BrokerError

ACK = {"none": AckPolicy.NONE, "written": AckPolicy.WRITTEN, "forced": AckPolicy.FORCED}


async def run(argv: list[str]) -> int:
    verb, args = argv[0], argv[1:]

    address = os.environ.get("BOOBLIK_BROKER")
    if not address:
        print("BOOBLIK_BROKER is not set (host:port)", file=sys.stderr)
        return 2

    async with await Connection.connect(address, timeout=15) as connection:
        try:
            if verb == "metadata":
                for info in (await connection.metadata(args[0])).get(args[0], []):
                    print(f"partition={info.partition} {info.log_start_offset} {info.high_watermark}")

            elif verb == "produce":
                # An empty field is a zero-length record and it is passed on rather than tidied
                # away: the broker refuses those, and the harness checks that the refusal reaches
                # the caller.
                payloads = [bytes.fromhex(field) for field in args[3].split(",")]
                result = await connection.produce(args[0], int(args[1]), payloads, ACK[args[2]])
                # None under AckPolicy.NONE, and printing nothing is the correct answer: no offset
                # exists yet. Awaiting one here is what the harness times out on.
                if result is not None:
                    print(f"baseOffset={result.base_offset}")
                    print(f"logEndOffset={result.log_end_offset}")

            elif verb == "produce-keyed":
                # Where the partitioner is exercised for real: the partition is chosen here, from
                # the key, because the broker never sees the key at all.
                topic = await connection.topic(args[0])
                chosen = topic.partition_for(bytes.fromhex(args[1]))
                result = await connection.produce(args[0], chosen, [bytes.fromhex(args[2])])
                print(f"partition={chosen}")
                print(f"baseOffset={result.base_offset}")

            elif verb == "fetch":
                # The raw call and not a Consumer: the checks are about what one FETCH answers —
                # a truncated tail, an empty response at the high watermark, a refusal past the
                # end — and a Consumer would smooth over exactly those.
                answer = await connection.fetch(args[0], int(args[1]), int(args[2]), int(args[3]))
                print(f"highWatermark={answer.high_watermark}")
                # Nothing whole and something partial: the next record is bigger than max_bytes and
                # never arrives, which a caller must not read as having caught up.
                if not answer.records and answer.truncated:
                    print(f"recordExceedsMaxBytes={answer.truncated_record_bytes}")
                elif answer.records:
                    for record in answer.records:
                        print(f"record={record.hex()}")

            else:
                print(f"unknown verb: {verb}", file=sys.stderr)
                return 2
        except BrokerError as refusal:
            # A refusal is a result, not a failure of this program: report it and exit zero.
            print(f"error={refusal.code.name}")
    return 0


def main(argv: list[str]) -> int:
    if not argv:
        print("usage: conformance_aio.py <verb> [args...]", file=sys.stderr)
        return 2

    if argv[0] == "capabilities":
        print("roles=producer,consumer")
        print("name=python-asyncio")
        return 0

    return asyncio.run(run(argv))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
