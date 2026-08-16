# FetchMode (/wiki/booblik-net/fetchmode)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `FetchMode` determines the data transfer strategy used by the broker to move log data from the storage layer to the network socket during a `FetchRequest`. It is a critical performance knob that dictates whether data passes through the JVM heap or stays within the OS page cache.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Client
    participant S as Session
    participant L as Log/Segment
    participant N as Network (Socket)

    alt FetchMode.ZERO_COPY
        S->>L: openFetch(offset, maxBytes)
        L-->>S: slice (segment + position)
        S->>N: transferFrom(slice.segment, pos, bytes)
        Note over S, N: Data moves via FileChannel.transferTo
    else FetchMode.HEAP
        S->>L: openFetch(offset, maxBytes)
        L-->>S: slice (segment + position)
        S->>S: staging(bytes) -> allocate/reuse
        S->>L: segment.readInto(pos, staging)
        S->>N: writeFully(staging)
        Note over S, N: Data moves through JVM Heap
    end"
/>

## FetchMode [#fetchmode]

The available strategies are defined in `BooblikServer.kt:37-43`:

| Mode        | Description                                                                                                           |
| ----------- | --------------------------------------------------------------------------------------------------------------------- |
| `ZERO_COPY` | Uses `FileChannel.transferTo` to move data from the page cache directly to the socket buffer, bypassing the JVM heap. |
| `HEAP`      | Reads data into a heap-allocated staging buffer before writing it to the connection.                                  |

## ZERO\_COPY mechanics [#zero_copy-mechanics]

When `FetchMode.ZERO_COPY` is selected, the `Session` utilizes the `Connection.transferFrom` method to perform a zero-copy transfer [`Session.kt:221-223`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L221-L223). This approach leverages the operating system's ability to move data from the file system cache directly to the network stack, avoiding the overhead of copying bytes into the application's memory space.

## HEAP staging and buffer reuse [#heap-staging-and-buffer-reuse]

In `HEAP` mode, the `Session` manages a reusable staging buffer to minimize allocation pressure [`Session.kt:289-294`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L289-L294). The lifecycle of this buffer is as follows:

1. **Allocation**: A buffer is allocated with an initial size of 64 KiB [`Session.kt:297`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L297).
2. **Growth**: If a request requires more space, the `staging` function allocates a new `ByteBuffer` of the required size [`Session.kt:290`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L290).
3. **Reuse**: The same buffer is cleared and reused for subsequent requests to avoid frequent GC cycles.

## Fetch slice lifecycle and retention [#fetch-slice-lifecycle-and-retention]

To prevent a long-running `FetchRequest` from blocking log retention, the `Session` manages data access through a "slice" mechanism [`Session.kt:206`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L206).

* **Opening**: A slice is opened only after the `maxWaitMillis` period has passed and the `highWatermark` is checked [`Session.kt:188`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L188).
* **Retention**: The slice is held open only during the actual I/O operation (the header and the body) [`Session.kt:212`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L212).
* **Closing**: The slice is automatically closed via a `use` block, ensuring that the log segment is not held open longer than necessary, which would otherwise prevent the retention policy from deleting old segments [`Session.kt:212`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L212).

## Fetch response integrity and truncation [#fetch-response-integrity-and-truncation]

The server ensures that responses respect the `maxBytes` constraint and segment boundaries:

* **Truncation**: If a record is cut off by the `maxBytes` limit, the `Session` marks the response as truncated [`ServerTest.kt:117`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ServerTest.kt#L117).
* **Segment Boundaries**: A fetch operation will stop at a segment boundary; if a request spans multiple segments, the client must perform subsequent fetches to retrieve data from the next segment [`ServerTest.kt:137`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ServerTest.kt#L137).
* **Integrity**: The `maxBytes` constraint is applied to the total bytes returned, and the `truncated` flag is used to signal to the client that the full record was not sent [`ServerTest.kt:117`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ServerTest.kt#L117).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                         | Lines     | What is there                                                                  |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------------------------------ |
| [`…/net/BooblikServer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L37-L43 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt") | `37-43`   | The `FetchMode` enum defining `ZERO_COPY` and `HEAP`.                          |
| [`…/net/Session.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L219-L231 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt")                 | `219-231` | The implementation of the `fetch` logic for both `ZERO_COPY` and `HEAP` modes. |
| [`…/net/ServerTest.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ServerTest.kt#L105-L122 "booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ServerTest.kt")        | `105-122` | Tests verifying `maxBytes` truncation and record integrity.                    |

## Behaviour that surprises [#behaviour-that-surprises]

* **Silent Requests**: When `AckPolicy.NONE` is used in a `ProduceRequest`, the server does not send any response frame at all, even if the write was successful [`Session.kt:169`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L169).
* **High Watermark vs. Log End**: A `FetchRequest` at the current `highWatermark` is considered a valid "caught-up" state and returns an empty response rather than an error [`Session.kt:197`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L197).
* **Slice Timing**: The `available` check for `minBytes` is performed by opening and immediately closing a slice to avoid holding a segment lock during the `awaitRecords` wait period [`Session.kt:276-277`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L276-L277).
