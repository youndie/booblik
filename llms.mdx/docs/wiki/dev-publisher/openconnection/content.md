# openConnection (/wiki/dev-publisher/openconnection)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `dev/publisher` module is responsible for simulating event streams and task queues to test the Booblik broker. Within this module, the `openConnection` mechanism ensures that the publisher can gracefully handle the lifecycle of the broker, specifically managing the transition from a container starting up to a state where the network socket is actually accepting connections.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant P as Publisher (Main.kt)
    participant B as Broker (Docker)
    participant C as BooblikConnection

    loop Retry Loop
        P->>B: Attempt connection (InetSocketAddress)
        alt Connection Refused/Failure
            B-->>P: Exception (failure.message)
            P->>P: delay(1000)
        else Connection Success
            B-->>P: BooblikConnection instance
        end
    end
    P->>C: Initialize Producer with SupervisorJob"
/>

## The `openConnection` retry loop [#the-openconnection-retry-loop]

The `openConnection` function implements a blocking retry mechanism to establish a `BooblikConnection`. It first resolves the broker's location into an `InetSocketAddress` using the host and port provided in the `PublisherConfig` ([`Main.kt:77`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L77)). If the connection attempt fails, the function catches the exception, prints a retry message including the failure reason, and waits for 1000 milliseconds before attempting to connect again ([`Main.kt:82-83`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L82-L83)).

## Broker availability and startup race conditions [#broker-availability-and-startup-race-conditions]

The retry loop is a defensive measure against startup race conditions in containerized environments. While [`docker-compose.yml:44-46`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/docker-compose.yml#L44-L46) uses `condition: service_healthy` to ensure the publisher only starts after the broker's health check passes, the code includes the loop because "should never" and "does not" are different claims ([`Main.kt:72-74`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L72-L74)). This prevents the publisher from crashing if the broker's process is running but the network stack is not yet ready to accept connections.

## BooblikConnection instantiation [#booblikconnection-instantiation]

Once a connection is successfully established, `openConnection` returns a new `BooblikConnection` instance ([`Main.kt:80`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L80)). Crucially, this connection is initialized with its own `CoroutineScope` configured with a `SupervisorJob` ([`Main.kt:80`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L80)). This ensures that failures in child coroutines within the connection do not automatically cancel the entire scope, allowing for more robust error handling during the publisher's execution.

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                            | Lines   | What is there                                                                                 |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | --------------------------------------------------------------------------------------------- |
| [`…/publisher/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L76-L86 "dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt") | `76-86` | The `openConnection` implementation containing the retry logic and `InetSocketAddress` setup. |
| [`dev/docker-compose.yml`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/docker-compose.yml#L44-L46 "dev/docker-compose.yml")                                                                                                            | `44-46` | The `depends_on` configuration using `service_healthy` to coordinate service startup.         |

## Behaviour that surprises [#behaviour-that-surprises]

* **Scope Isolation**: The `BooblikConnection` created in `openConnection` is given a new `CoroutineScope` with a `SupervisorJob` rather than using the caller's scope, which prevents a connection failure from propagating up and killing the entire publisher application ([`Main.kt:80`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L80)).
* **The "Should Never" Clause**: Despite the orchestration provided by Docker Compose, the `openConnection` function explicitly includes a `while(true)` loop to handle the edge case where a broker is "not answering yet" ([`Main.kt:78-82`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L78-L82)).
