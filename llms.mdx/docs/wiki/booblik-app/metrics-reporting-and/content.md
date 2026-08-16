# Metrics reporting and retention (/wiki/booblik-app/metrics-reporting-and)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    subgraph &#x22;Background Coroutines (Main.kt)&#x22;
        M[reportMetrics] -->|Calculates rates| P[println]
        R[applyRetention] -->|Triggers| B[broker.applyRetention]
    end

    subgraph &#x22;Broker & Storage&#x22;
        B -->|Removes segments| S[Log Segments]
        B -->|Provides snapshot| M
    end

    subgraph &#x22;Shutdown Sequence&#x22;
        SH[Shutdown Hook] -->|1. Cancel| M
        SH -->|2. Cancel| R
        SH -->|3. Close| B
    end"
/>

## Metrics snapshotting and rate reporting [#metrics-snapshotting-and-rate-reporting]

The `Metrics` class provides a snapshot of the current state of the `Broker`, which is used to calculate performance. Instead of printing cumulative counters—which would require the operator to perform manual differentiation during an incident—the `reportMetrics` function transforms these raw values into rates (e.g., records per second) by comparing the current snapshot against a previous one over a specific time interval [`Main.kt:119-123`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L119-L123). This interval is configurable via `metricsIntervalMillis` in the `BooblikConfig` [`BooblikConfig.kt:47`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L47).

## The `applyRetention` timer [#the-applyretention-timer]

The broker itself is designed to be stateless regarding time, allowing tests to advance time manually by calling `broker.applyRetention` directly [`Main.kt:131-133`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L131-L133). The actual temporal logic is encapsulated in a dedicated background coroutine launched in `Main.kt:86` [`Main.kt:86`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L86). This loop uses `delay(config.retentionCheckMillis)` to determine when to trigger the retention logic [`Main.kt:141`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L141), ensuring that the "when" is decoupled from the "how" of the broker's internal logic [`Main.kt:136-144`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L136-L144).

## Retention by size and age [#retention-by-size-and-age]

Retention is governed by two optional parameters defined in `BooblikConfig` [`BooblikConfig.kt:42-43`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L42-L43):

| Key               | Type    | Description                                         |
| ----------------- | ------- | --------------------------------------------------- |
| `retentionBytes`  | `Long?` | Maximum total size of the log to keep per partition |
| `retentionMillis` | `Long?` | Maximum age of a segment to keep                    |

When `applyRetention` is called, the broker removes segments that exceed these thresholds [`Main.kt:142`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L142).

## Shutdown sequence and data integrity [#shutdown-sequence-and-data-integrity]

To prevent data loss, the application follows a strict, layered shutdown sequence within the `Runtime` shutdown hook [`Main.kt:91-103`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L91-L103). The order is critical:

1. The `BooblikServer` is closed first [`Main.kt:94`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L94).
2. Background coroutines (metrics and retention) are cancelled via `background.cancel()` to stop any pending tasks [`Main.kt:95`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L95).
3. The `broker` is closed last [`Main.kt:98`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L98). This ensures that the broker's writers are still active while the server is shutting down, allowing any accepted batches to reach the disk before the underlying log is closed [`Main.kt:97-98`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L97-L98).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                         | Lines    | What is there                                                         |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | --------------------------------------------------------------------- |
| [`…/app/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L84-L104 "booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt")                           | `84-104` | The main loop, shutdown hook, and background coroutine orchestration. |
| [`…/app/BooblikConfig.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L42-L43 "booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt") | `42-43`  | Configuration properties for retention and metrics intervals.         |

## Behaviour that surprises [#behaviour-that-surprises]

* **Decoupled Time**: The `Broker` does not have its own clock; it relies on an external caller (like the `applyRetention` loop in `Main.kt`) to tell it when to perform maintenance [`Main.kt:131-133`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L131-L133).
* **Shutdown Order**: The `broker` is closed *after* the background coroutines are cancelled to ensure that any data currently being processed by the server can be flushed to the log before the log itself is closed [`Main.kt:95-98`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L95-L98).
* **Rate-based Metrics**: `reportMetrics` specifically avoids printing cumulative counters to prevent the need for manual differentiation during incident response [`Main.kt:110-111`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L110-L111).
