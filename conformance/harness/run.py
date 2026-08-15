"""Runs the conformance checks against one client.

    BOOBLIK_BROKER=localhost:9092 python3 conformance/harness/run.py '<client command>'

The broker must already be running with `BOOBLIK_TOPICS=conformance:4,single:1`. Starting one is
`conformance/run.sh`'s job, kept separate so these checks can also be pointed at a broker somebody
else is running — a staging one, or one inside a compose network.
"""

import os
import pathlib
import sys
import traceback

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import scenarios  # noqa: E402
from client import Client, ClientError, indent  # noqa: E402

VECTORS = pathlib.Path(__file__).resolve().parents[1] / "vectors" / "partitioner-fnv1a.tsv"


def load_vectors(path: pathlib.Path) -> list:
    """Reads the TSV, taking the last comment line as the column header.

    The vectors are the specification of the partitioner, so the harness reads the same file the
    unit tests do. A harness with its own copy of the expected partitions would keep passing after
    the specification changed, which is the one thing it must not do.
    """
    header, rows = None, []
    for line in path.read_text().splitlines():
        if line.startswith("#"):
            header = line[1:].strip().split("\t")
            continue
        if line.strip():
            rows.append(dict(zip(header, line.split("\t"))))
    return rows


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: run.py '<client command>'", file=sys.stderr)
        return 2

    broker = os.environ.get("BOOBLIK_BROKER")
    if not broker:
        print("BOOBLIK_BROKER is not set (host:port)", file=sys.stderr)
        return 2
    host, _, port = broker.partition(":")

    client = Client(sys.argv[1], broker)
    print(f"→ conformance against {client.argv[0]} at {broker}")

    try:
        client.load_capabilities()
    except ClientError as failure:
        print(f"\n   `capabilities` failed, so nothing else can be checked:\n{indent(str(failure))}")
        return 1

    print(f"   {client.name} declares: {', '.join(sorted(client.roles))}\n")

    context = scenarios.Context(client, host, int(port), load_vectors(VECTORS))
    passed = failed = skipped = 0

    for entry in scenarios.CHECKS:
        title, role = entry["title"], entry["role"]
        if not client.declares(role):
            print(f"   － {title}  (no {role} role declared)")
            skipped += 1
            continue
        try:
            entry["run"](context)
        except AssertionError as failure:
            print(f"   ✗ {title}\n{indent(str(failure))}")
            failed += 1
        except ClientError as failure:
            print(f"   ✗ {title}\n{indent(str(failure))}")
            failed += 1
        except Exception:  # noqa: BLE001 — a harness that hides its own crash is worse than useless
            print(f"   ✗ {title}  (the harness itself failed)\n{indent(traceback.format_exc())}")
            failed += 1
        else:
            print(f"   ✓ {title}")
            passed += 1

    print(f"\n   {passed} passed, {failed} failed, {skipped} not applicable")

    # A run where everything was skipped is not a pass. A client that declared no role it actually
    # implements would otherwise report success having been asked nothing at all.
    if passed == 0 and failed == 0:
        print("   nothing was checked — that is a failure, not a pass")
        return 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
