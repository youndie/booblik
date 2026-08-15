// Holds this client to the golden vectors, computed by another implementation in another language.
//
// Agreeing with itself is what a wrong partitioner also does; agreeing with an independent reading
// of the written specification is the property that matters. **If this fails, this code is wrong —
// not the vectors.**

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

import { FNV_OFFSET_BASIS, FNV_PRIME, fnv1a32, partitionFor } from "../src/partition.js";

// The fold columns, in the order the vector file's header gives them.
const PARTITION_COUNTS = [1, 2, 3, 4, 16, 64];

// Walks up rather than trusting the working directory: the runner starts in the package directory
// and an editor may not, and a fixture that resolves in one but not the other gets deleted by
// whoever hits it second.
function findUpwards(relative) {
  let directory = path.dirname(fileURLToPath(import.meta.url));
  for (;;) {
    const candidate = path.join(directory, relative);
    if (fs.existsSync(candidate)) return candidate;
    const parent = path.dirname(directory);
    if (parent === directory) throw new Error(`${relative} not found above the test directory`);
    directory = parent;
  }
}

function readVectors(name) {
  const content = fs.readFileSync(findUpwards(path.join("conformance", "vectors", name)), "utf8");
  return content
    .split("\n")
    .filter((line) => line !== "" && !line.startsWith("#"))
    // `split` keeps empty fields, so the empty-key vector arrives as a leading "" — which is the
    // vector a hand-written parser drops first.
    .map((line) => line.split("\t"));
}

test("the partitioner matches the golden vectors", () => {
  const rows = readVectors("partitioner-fnv1a.tsv");
  assert.ok(rows.length > 0, "no vectors loaded");

  for (const row of rows) {
    const name = row.at(-1);
    const key = Buffer.from(row[0], "hex");

    assert.equal(fnv1a32(key), Number(row[1]), `hash of «${name}»`);
    PARTITION_COUNTS.forEach((partitions, index) => {
      assert.equal(
        partitionFor(key, partitions),
        Number(row[2 + index]),
        `partition of «${name}» among ${partitions}`,
      );
    });
  }
});

test("the hash stays a 32-bit unsigned integer", () => {
  // JavaScript numbers are doubles, so a plain multiply leaves exact integer range after about
  // three bytes and starts rounding silently. This is the language's own version of the trap the
  // vectors exist for, and a long key is where it first shows.
  for (const key of [Buffer.alloc(0), Buffer.from([0x80]), Buffer.from("9".repeat(500))]) {
    const hash = fnv1a32(key);
    assert.ok(Number.isInteger(hash), "the hash stopped being an integer");
    assert.ok(hash >= 0 && hash < 2 ** 32, `${hash} is outside 32 unsigned bits`);
  }
});

test("high bytes are unsigned", () => {
  // 0x80 read as −128 would sign-extend before the XOR. Iterating a Uint8Array cannot do that, but
  // this is the line every other language's port gets wrong, so it is asserted rather than assumed.
  const signExtended = Math.imul(FNV_OFFSET_BASIS ^ 0xffffff80, FNV_PRIME) >>> 0;
  assert.notEqual(fnv1a32(Buffer.from([0x80])), signExtended);
});

test("no partitions is refused", () => {
  assert.throws(() => partitionFor(Buffer.from("k"), 0), RangeError);
});
