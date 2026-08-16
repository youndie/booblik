# booblik-native-conformance (/wiki/booblik-native-conformance)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 3. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant H as Harness
    participant C as Conformance Executable
    participant B as Booblik Broker
    participant P as Probe (Benchmark)

    H->>C: Execute with verb + args
    C->>B: Send Request (via BooblikConnection)
    B-->>C: Send Response
    C-->>H: Print stdout/stderr
    
    Note over P: Benchmarking Modes
    P->>P: DIRECT (1 connection/thread)
    P->>P: BATCHED (1 connection/accumulator)
    P->>P: BATCHED_NOT_AWAITED (Pipelined)"
/>

## The conformance executable [#the-conformance-executable]

The `booblik-native-conformance` module serves as a test harness for the Kotlin/Native client, designed to verify the protocol implementation against a real broker. As specified in [`build.gradle.kts:18-32`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/build.gradle.kts#L18-L32), the module defines two distinct Kotlin/Native binaries: a `conformance` executable and a `probe` executable, both targeting `linuxX64` and `macosArm64`.

More: [The conformance executable](booblik-native-conformance/the-conformance-executable)

## The conformance verb interface [#the-conformance-verb-interface]

The `conformance` executable provides a command-line interface to exercise specific protocol operations. According to [`Main.kt:47-68`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L47-L68), the available verbs are:

| Verb            | Description                                                                                   |
| --------------- | --------------------------------------------------------------------------------------------- |
| `metadata`      | Retrieves topic metadata, including partition offsets (`Main.kt:73-86`).                      |
| `produce`       | Sends records to a specific partition with a chosen `AckPolicy` (`Main.kt:89-115`).           |
| `produce-keyed` | Uses a partitioner to select a partition based on a key before producing (`Main.kt:121-135`). |
| `fetch`         | Retrieves records from a partition at a specific offset (`Main.kt:143-164`).                  |

More: [The conformance verb interface](booblik-native-conformance/the-conformance-verb)

## The probe performance measurement [#the-probe-performance-measurement]

The `probe` executable is used to measure the throughput of the native client under different concurrency models. As implemented in [`Probe.kt:65-75`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt#L65-L75), the benchmark compares throughput (records per second) across different numbers of callers (1, 8, and 64) to determine the efficiency of the accumulator.

More: [The probe performance measurement](booblik-native-conformance/the-probe-performance)

## The DIRECT mode [#the-direct-mode]

In `DIRECT` mode, the system operates without an accumulator. As described in [`Probe.kt:79-80`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt#L79-L80), each caller uses its own `BooblikConnection` and sends every record as its own request, awaiting the response before proceeding.

## The BATCHED mode [#the-batched-mode]

The `BATCHED` mode utilizes an accumulator to group multiple records into a single request. According to [`Probe.kt:82-83`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt#L82-L83), all callers share a single connection and a single accumulator, but they still await the result of each `send` operation before moving to the next record.

## The BATCHED\_NOT\_AWAITED mode [#the-batched_not_awaited-mode]

This mode implements a pipelined approach to batching. As detailed in [`Probe.kt:85-87`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt#L85-L87), multiple records are queued via `producer.send` without being immediately awaited, allowing the accumulator to build larger batches by overlapping requests.

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                                                 | Lines   | What is there                                                    |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------- | ---------------------------------------------------------------- |
| [`booblik-native-conformance/build.gradle.kts`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/build.gradle.kts#L18-L32 "booblik-native-conformance/build.gradle.kts")                                                                                                  | `18-32` | Configuration of Kotlin/Native targets and binary entry points.  |
| [`…/conformance/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L47-L68 "booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt")    | `47-68` | The main command-line dispatch logic for the conformance client. |
| [`…/conformance/Probe.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt#L79-L87 "booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt") | `79-87` | Definition of the different benchmarking modes.                  |

## Behaviour that surprise [#behaviour-that-surprise]

* The `conformance` executable's `main` function is designed to return exit code 0 even if the broker refuses a request, provided the refusal is a valid protocol result (`Main.kt:19-20`).
* In `BATCHED_NOT_AWAITED` mode, the `producer.send` calls are collected into a list and then `awaitAll` is called, which allows the `lingerMillis` window to fill by having more than one record in flight (`Probe.kt:140-145`).
* The `verify` function in the probe ensures that no two records are assigned the same `Offset` and that the total count of returned offsets matches the expected number of sent records (`Probe.kt:174-185`).
