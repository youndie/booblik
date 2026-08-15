"""Holds this client to the golden vectors, computed by another implementation in another language.

Agreeing with itself is what a wrong partitioner also does; agreeing with an independent reading of
the written specification is the property that matters. **If this fails, this code is wrong — not
the vectors.**
"""

import pathlib
import unittest

from booblik.partition import FNV_OFFSET_BASIS, FNV_PRIME, fnv1a32, partition_for

# The fold columns, in the order the vector file's header gives them.
PARTITION_COUNTS = [1, 2, 3, 4, 16, 64]


def _find_upwards(relative: str) -> pathlib.Path:
    """Walks up rather than trusting the working directory: the test runner starts in the package
    directory and an editor may not, and a fixture that resolves in one but not the other gets
    deleted by whoever hits it second."""
    directory = pathlib.Path(__file__).resolve().parent
    while True:
        candidate = directory / relative
        if candidate.exists():
            return candidate
        if directory.parent == directory:
            raise FileNotFoundError(relative)
        directory = directory.parent


def _read_vectors(name: str) -> list[list[str]]:
    path = _find_upwards(f"conformance/vectors/{name}")
    rows = []
    for line in path.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        # `split` keeps empty fields, so the empty-key vector arrives as a leading "" — which is
        # the vector a hand-written parser drops first.
        rows.append(line.split("\t"))
    return rows


class PartitionerVectorsTest(unittest.TestCase):
    def test_matches_the_golden_vectors(self):
        rows = _read_vectors("partitioner-fnv1a.tsv")
        self.assertTrue(rows, "no vectors loaded")

        for row in rows:
            name = row[-1]
            key = bytes.fromhex(row[0])

            with self.subTest(vector=name):
                self.assertEqual(int(row[1]), fnv1a32(key), f"hash of «{name}»")
                for index, partitions in enumerate(PARTITION_COUNTS):
                    self.assertEqual(
                        int(row[2 + index]),
                        partition_for(key, partitions),
                        f"partition of «{name}» among {partitions}",
                    )

    def test_the_hash_stays_inside_thirty_two_bits(self):
        """Python's integers do not overflow, so a missing mask produces an ever-growing number that
        agrees with nothing. This is the language's own version of the trap the vectors exist for,
        and it is asserted directly because a long key is where it first shows."""
        for key in (b"", b"\x80", bytes(range(256)), b"9" * 500):
            with self.subTest(length=len(key)):
                self.assertLess(fnv1a32(key), 1 << 32)
                self.assertGreaterEqual(fnv1a32(key), 0)

    def test_high_bytes_are_unsigned(self):
        """0x80 read as −128 would sign-extend before the XOR. Python cannot do that by accident —
        iterating bytes yields unsigned ints — but this is the line every other language's port gets
        wrong, so the property is asserted rather than assumed."""
        sign_extended = ((FNV_OFFSET_BASIS ^ 0xFFFFFF80) * FNV_PRIME) & 0xFFFFFFFF
        self.assertNotEqual(sign_extended, fnv1a32(b"\x80"))

    def test_no_partitions_is_refused(self):
        with self.assertRaises(ValueError):
            partition_for(b"k", 0)


if __name__ == "__main__":
    unittest.main()
