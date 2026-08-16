# The probe performance measurement (/wiki/booblik-native-conformance/the-probe-performance)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Callers/Consumers
    participant P as Producer/Subscriber
    participant A as Accumulator/Broker
    participant D as Disk/Storage

    Note over C, D: Mode.DIRECT
    C->>A: Send Record (Awaited)
    A->>D: Write
    D-->>A: Ack
    A-->>C: Offset

    Note over C, D: Mode.BATCHED_NOT_AWAITED
    C->>A: Send Record (Async)
    C->>A: Send Record (Async)
    Note right of A: Linger Window
    A->>D: Batch Write
    D-->>A: Ack
    A-->>C: Offset (Awaited at end)"
/>

## The `probe` function [#the-probe-function]

The entry point for manual performance verification, comparing different execution modes and scaling across multiple callers. It reads the broker address from the `BOOBLIK_BROKER` environment variable [`Probe.kt:52-56`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt#L52-L56) and iterates through a set of caller counts (1, 8, and 64) to measure throughput [`Probe.kt:65-75`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt#L65-L75).

## The `Mode` enumeration [#the-mode-enumeration]

The three execution strategies: `DIRECT` (no accumulator), `BATCHED` (awaited), and `BATCHED_NOT_AWAITED` (pipelined).

| Mode                  | Description                                                                                                                                                                                                                                                                                               |
| --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `DIRECT`              | Every record itsown request, awaited before the next. What a caller without one does. [`Probe.kt:79-81`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt#L79-L81) |
| `BATCHED`             | Through the accumulator, still awaited before the next — a request handler's pattern. [`Probe.kt:83`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt#L83)        |
| `BATCHED_NOT_AWAITED` | Through the accumulator, awaited at the end. The case the accumulator exists for. [`Probe.kt:86-92`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt#L86-L92)     |

## The `verify` mechanism [#the-verify-mechanism]

Integrity checks ensuring every record is accounted for and that all produced offsets are distinct. The `verify` function checks if the number of returned offsets matches the expected count and ensures no two records were given the same offset [`Probe.kt:173-185`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt#L173-L185).

## The `SubscriptionProbe` lifecycle [#the-subscriptionprobe-lifecycle]

Measuring the cost of `IDLE` vs `THROUGHPUT` modes and the performance overhead of the `Flow` abstraction via `bare fetch loop`, `cold flow { }`, and `buffer()`. The probe measures the number of fetch requests answered by the server during a specific duration [`SubscriptionProbe.kt:208-225`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SubscriptionProbe.kt#L208-L225) and compares three rungs of the `readThreeWays` ladder to isolate the cost of the `Flow` machinery and the decoupling provided by `buffer()` [`SubscriptionProbe.kt:289-329`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SubscriptionProbe.kt#L289-L329).

## The `MeasurementDir` constraint [#the-measurementdir-constraint]

The refusal to run measurements on volatile filesystems (like `tmpfs` or `ramfs`) to prevent measuring memory instead of disk. The `create` function checks the file store type and throws an `IllegalStateException` if the directory is on a volatile filesystem, as `fsync` is a no-op there [`MeasurementDir.kt:39-48`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L39-L48).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                                                 | Lines     | What is there                                                                 |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------- | ----------------------------------------------------------------------------- |
| [`…/conformance/Probe.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt#L65-L75 "booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt") | `65-75`   | The main loop iterating through different caller counts for benchmarking.     |
| [`…/probe/SubscriptionProbe.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SubscriptionProbe.kt#L289-L329 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SubscriptionProbe.kt")     | `289-329` | The implementation of the three-way comparison for Flow abstraction overhead. |
| [`…/benchmark/MeasurementDir.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L39-L48 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt")                        | `39-48`   | Logic to prevent measurements on volatile filesystems like `tmpfs`.           |

## Behaviour that surprises [#behaviour-that-surprises]

* The `SubscriptionProbe` can report that a `Flow` is faster than the loop it wraps if the comparison is not performed in sequence, because `callbackFlow` introduces real concurrency (decoupling) that a bare loop lacks [`SubscriptionProbe.kt:272-277`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SubscriptionProbe.kt#L272-L277).
* The `MeasurementDir` will actively refuse to run a test if it detects a `tmpfs` or `ramfs` filesystem to avoid reporting "fiction" numbers where `fsync` is a no-op [`MeasurementDir.kt:32-48`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L32-L48).
