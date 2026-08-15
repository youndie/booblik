package booblik

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// Holds this client to the golden vectors, which were computed by a different implementation in a
// different language (conformance/vectors/generate.py). Agreeing with itself is what a wrong
// partitioner also does; agreeing with an independent reading of the written specification is the
// property that matters.
//
// If this fails, this code is wrong — not the vectors.
func TestPartitionerMatchesGoldenVectors(t *testing.T) {
	rows := readVectors(t, "partitioner-fnv1a.tsv")
	if len(rows) == 0 {
		t.Fatal("no vectors loaded")
	}

	// The fold columns, in the order the file's header gives them.
	counts := []int{1, 2, 3, 4, 16, 64}

	for _, row := range rows {
		name := row[len(row)-1]
		key := decodeHex(t, row[0])

		want, err := strconv.ParseUint(row[1], 10, 32)
		if err != nil {
			t.Fatalf("vector %q has an unreadable hash %q: %v", name, row[1], err)
		}
		if got := FNV1a(key); uint64(got) != want {
			t.Errorf("FNV1a(%q) = %d, vectors say %d", name, got, want)
		}

		for i, partitions := range counts {
			want, err := strconv.Atoi(row[2+i])
			if err != nil {
				t.Fatalf("vector %q column p%d is unreadable: %v", name, partitions, err)
			}
			if got := PartitionFor(key, partitions); got != want {
				t.Errorf("PartitionFor(%q, %d) = %d, vectors say %d", name, partitions, got, want)
			}
		}
	}
}

// Go is the language where the signed-byte trap cannot happen — ranging a []byte yields uint8 — so
// this asserts the property directly rather than trusting that it stays true if the loop is ever
// rewritten with, say, an int8 conversion in it.
func TestHighBytesAreUnsigned(t *testing.T) {
	// A signed reading sign-extends 0x80 to 0xFFFFFF80 before the XOR. Go cannot do that by
	// accident, but this is the exact line every other language's port gets wrong, so the property
	// is asserted here rather than assumed — including against somebody later rewriting the loop
	// with a conversion in it.
	// Through a variable: as a constant expression this overflows uint32 and Go rejects it at
	// compile time, even though the same multiplication wraps quietly at runtime — which is the
	// arithmetic the hash is defined in.
	mixed := fnvOffsetBasis ^ uint32(0xFFFFFF80)
	signExtended := mixed * fnvPrime
	if got := FNV1a([]byte{0x80}); got == signExtended {
		t.Fatalf("FNV1a treated 0x80 as −128: got %d", got)
	}
}

func TestPartitionForRejectsNoPartitions(t *testing.T) {
	defer func() {
		if recover() == nil {
			t.Fatal("expected a panic: there is no partition to pick, and 0 may not exist")
		}
	}()
	PartitionFor([]byte("k"), 0)
}

func readVectors(t *testing.T, name string) [][]string {
	t.Helper()

	path := findUpwards(t, filepath.Join("conformance", "vectors", name))
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}

	var rows [][]string
	for _, line := range strings.Split(string(content), "\n") {
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		// strings.Split keeps empty fields, so the empty-key vector arrives as a leading "" —
		// which is the vector a hand-written parser drops first.
		rows = append(rows, strings.Split(line, "\t"))
	}
	return rows
}

// Walks up rather than trusting the working directory: `go test ./...` runs from the package
// directory and an editor may not, and a fixture that resolves in one but not the other gets
// deleted by whoever hits it second.
func findUpwards(t *testing.T, relative string) string {
	t.Helper()

	dir, err := os.Getwd()
	if err != nil {
		t.Fatalf("getwd: %v", err)
	}
	for {
		candidate := filepath.Join(dir, relative)
		if _, err := os.Stat(candidate); err == nil {
			return candidate
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatalf("%s not found above the working directory", relative)
		}
		dir = parent
	}
}

func decodeHex(t *testing.T, text string) []byte {
	t.Helper()

	out := make([]byte, len(text)/2)
	for i := range out {
		value, err := strconv.ParseUint(text[i*2:i*2+2], 16, 8)
		if err != nil {
			t.Fatalf("bad hex %q: %v", text, err)
		}
		out[i] = byte(value)
	}
	return out
}
