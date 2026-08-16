# Socket (/wiki/booblik-native/socket)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `Socket` class in the `booblik-native` module provides a low-level, blocking TCP implementation using POSIX primitives. It serves as the foundation for native clients, handling the raw mechanics of host resolution, connection establishment, and reliable byte transmission.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant App as Caller
    participant Socket as Socket (Native)
    participant Libc as POSIX (libc)
    participant Net as Network Stack

    App->>Socket: connect(address)
    Socket->>Libc: getaddrinfo(host, port)
    Libc-->>Socket: addrinfo list
    loop for each candidate
        Socket->>Libc: socket()
        Socket->>Libc: connect(fd, addr)
        alt connection successful
            Socket->>Libc: setsockopt(TCP_NODELAY)
            Socket-->>App: Socket instance
        else connection failed
            Socket->>Libc: close(fd)
        end
    end
    App->>Socket: writeFully(bytes)
    loop until all bytes sent
        Socket->>Libc: send(fd, buffer, len)
    end
    App->>Socket: readFully(count)
    loop until count reached
        Socket->>Libc: recv(fd, buffer, len)
    end
    App->>Socket: close()
    Socket->>Libc: close(fd)"
/>

## Socket lifecycle and connection establishment [#socket-lifecycle-and-connection-establishment]

The connection process begins with the `connect` factory method, which parses the input string to separate the host from the port ([`Socket.kt:46-50`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L46-L50)). To ensure cross-platform compatibility between Linux and macOS, the implementation uses `getaddrinfo` to resolve addresses, which allows it to handle both host names and dotted quads while abstracting away differences in `sockaddr` layouts ([`Socket.kt:36-39`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L36-L39)). The instantiation process involves iterating through the linked list of `addrinfo` candidates provided by `getaddrinfo`, attempting to create a socket and connect until a successful descriptor is obtained ([`Socket.kt:65-72`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L65-L72)).

## TCP\_NODELAY and Nagle's algorithm [#tcp_nodelay-and-nagles-algorithm]

To optimize performance for the specific traffic pattern of the protocol, the `enableNoDelay` method is called immediately after a successful connection ([`Socket.kt:71`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L71)). This method uses `setsockopt` with the `TCP_NODELAY` flag to disable Nagle's algorithm ([`Socket.kt:94-98`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L94-L98)). This is critical because the requests are small and involve round trips; failing to set this would cause Nagle's algorithm to add unnecessary delays to each request-response cycle ([`Socket.kt:86-88`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L86-L88)).

## writeFully and send mechanics [#writefully-and-send-mechanics]

The `writeFully` method ensures that a `ByteArray` is completely transmitted over the wire by using a loop that tracks the number of bytes written ([`Socket.kt:107-108`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L107-L108)). Inside this loop, the `send` function is called repeatedly with the remaining buffer length until the entire payload is sent ([`Socket.kt:110-113`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L110-L113)). If `send` returns a value less than or equal to zero, a `ConnectionException` is thrown, indicating the connection was closed during the transmission ([`Socket.kt:116`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L116)).

## readFully and connection termination [#readfully-and-connection-termination]

The `readFully` method implements a loop to fill a `ByteArray` of a specific size using the `recv` function ([`Socket.kt:126-127`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L126-L127)). A critical edge case occurs when `recv` returns zero, which signifies that the broker has performed an orderly close of the connection ([`Socket.kt:134-135`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L134-L135)). In such cases, if the requested number of bytes has not been fully received, a `ConnectionException` is thrown because the caller did not receive the full frame it expected ([`Socket.kt:136-137`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L136-L137)).

## Socket close [#socket-close]

The `close` method is responsible for the final stage of the socket lifecycle by releasing the underlying system resource ([`Socket.kt:145-146`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L145-L146)). It calls the POSIX `close` function on the stored file descriptor to ensure the socket is properly released back to the operating system ([`Socket.kt:146`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L146)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                 | Lines     | What is there                                               |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------- | ----------------------------------------------------------- |
| [`…/native/Socket.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L42-L44 "booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt")   | `42-44`   | The `Socket` class declaration and its private constructor. |
| [`…/native/Socket.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L46-L83 "booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt")   | `46-83`   | The `connect` companion object method and its logic.        |
| [`…/native/Socket.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L105-L120 "booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt") | `105-120` | The `writeFully` implementation for sending bytes.          |
| [`…/native/Socket.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L122-L143 "booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt") | `122-143` | The `readFully` implementation for receiving bytes.         |

## Behaviour that surprise [#behaviour-that-surprise]

* The `connect` method in `Socket` will throw a `ConnectionException` if it exhausts all `addrinfo` candidates without successfully establishing a connection ([`Socket.kt:81`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L81)).
* In `readFully`, a return value of zero from `recv` is treated as a failure if the requested byte count has not been met, rather than being treated as a successful end-of-stream ([`Socket.kt:136-137`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L136-L137)).
