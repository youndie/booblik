package ru.workinprogress.booblik.net.client

/**
 * Decides which partition a record goes to, from a key the broker never sees.
 *
 * Keys are a **client-side** idea here, and permanently so: the record format has no room for one,
 * so the broker cannot route by key, cannot compact by key, and will never be able to. What it can
 * do is store whatever bytes it is given in whatever partition it is told, which is all a
 * partitioner needs.
 */
fun interface Partitioner {
    fun partitionFor(
        key: ByteArray,
        partitions: Int,
    ): Int

    companion object {
        /**
         * FNV-1a over the key as **unsigned** bytes, folded by unsigned remainder. The default
         * since 0.3.0, and the algorithm written out in `docs/api/protocol-wire.md` §7.
         *
         * A partitioner has exactly one property to have: two publishers must agree, or records
         * under one key land in two partitions and per-key order — the thing partitions exist to
         * give — is silently gone. Agreement between two JVMs is easy and was all this needed while
         * the only client was this one. Agreement between languages is a different requirement, and
         * it is met by an algorithm specified in bytes rather than delegated to a platform's
         * `hashCode`.
         *
         * FNV-1a and not murmur2 (Kafka's): four lines in any language against twenty, with no
         * unsigned right shift and no tail switch to get wrong. Distribution quality decides less
         * here than it looks — the fold is over a partition count in single digits.
         *
         * **`and 0xFF` is the whole point.** Kotlin's `Byte` is signed, so `hash xor byte` would
         * sign-extend `0x80` to `0xFFFFFF80` and disagree with any language whose bytes are not.
         * The vectors in `conformance/vectors/partitioner-fnv1a.tsv` carry `0x80` for exactly this.
         */
        val Fnv1a =
            Partitioner { key, partitions ->
                // Unsigned remainder, so the specification never has to say how a language signs
                // its integers — the one place `floorMod` would have hidden a portability question.
                (fnv1a32(key).toUInt() % partitions.toUInt()).toInt()
            }

        /**
         * The 32-bit FNV-1a hash behind [Fnv1a], as an `Int` holding 32 unsigned bits.
         *
         * Public because the conformance vectors carry the hash itself and not only the partition
         * it folds to: a client checked on the fold alone can be wrong about the hash and right
         * about six remainders, and then the next partition count anyone picks exposes it.
         */
        fun fnv1a32(key: ByteArray): Int {
            var hash = FNV_OFFSET_BASIS
            for (byte in key) {
                hash = (hash xor (byte.toInt() and 0xFF)) * FNV_PRIME
            }
            return hash
        }

        /**
         * `Arrays.hashCode` over the key, folded by `floorMod` — the default **before 0.3.0**.
         *
         * Kept, and renamed to say what it is: a JVM algorithm, not a wire contract. Being
         * specified by the JDK makes two JVMs agree and says nothing to anybody else; a Go or
         * Python publisher reimplementing it diverges on any byte `>= 0x80`, because its bytes are
         * unsigned and Java's are not. Nothing fails when that happens — the record goes to a
         * partition that exists, just not the same one.
         *
         * Exists to keep reading data that was written with it. A topic that changes partitioner
         * mid-life does not lose records, but it does lose per-key order across the change, so the
         * cheap moment to change is before there is a topic.
         */
        val JavaArrayHash =
            Partitioner { key, partitions ->
                // `Math.floorMod` written out: it is JVM-only, and this file compiles for Native
                // too since M-134. For a positive divisor the two agree exactly.
                ((key.contentHashCode() % partitions) + partitions) % partitions
            }

        private const val FNV_OFFSET_BASIS = -0x7EE3623B // 2166136261
        private const val FNV_PRIME = 0x01000193 // 16777619
    }
}
