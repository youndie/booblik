package booblik

// The partitioner, specified in docs/api/protocol-wire.md §7 and pinned by the golden vectors in
// conformance/vectors/partitioner-fnv1a.tsv.
//
// It matters more than its size suggests. The broker never sees the key — the record format has no
// room for one — so the client picks the partition and sends the number. Two publishers that hash a
// key differently put it in two partitions, and per-key order, which is what partitions are for,
// is gone. Nothing errors when that happens: the record lands in a partition that exists, just not
// the same one.

const (
	fnvOffsetBasis uint32 = 0x811C9DC5
	fnvPrime       uint32 = 0x01000193
)

// FNV1a is the 32-bit FNV-1a hash of key, over its bytes as unsigned values.
//
// Go is the language where the usual trap does not exist: ranging over a []byte yields byte, which
// is uint8, so 0x80 is 128 here and cannot accidentally be −128. That is worth stating rather than
// relying on — the vectors carry 0x80 precisely because Java, Kotlin, C# and Rust all need a cast
// at this line, and this implementation is the one their authors will read.
func FNV1a(key []byte) uint32 {
	hash := fnvOffsetBasis
	for _, b := range key {
		hash = (hash ^ uint32(b)) * fnvPrime
	}
	return hash
}

// PartitionFor folds the hash of key into [0, partitions).
//
// Unsigned remainder, which is the whole reason the specification never has to say how a language
// signs its integers. It panics on a non-positive count rather than returning something: there is
// no partition to pick, and returning 0 would send records to a partition that may not exist.
func PartitionFor(key []byte, partitions int) int {
	if partitions <= 0 {
		panic("booblik: PartitionFor needs at least one partition")
	}
	return int(FNV1a(key) % uint32(partitions))
}
