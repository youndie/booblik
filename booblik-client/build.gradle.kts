plugins {
    alias(libs.plugins.kotlin.jvm)
}

apply(from = rootProject.file("publishing.gradle.kts"))

/*
 * The client and the shared codec, kept apart from the server.
 *
 * The split is not cosmetic. First, a consumer of the library has no use for the selector, the
 * session and the broker — it does not call them, but it does compile and load them. Second, this
 * is exactly the work a move to Kotlin Multiplatform would require (decision Р8): what is not
 * portable in the client is enumerable — sockets, `ByteBuffer`, two primitives from
 * `java.util.concurrent` and `CRC32C` — and it now sits in one place instead of being spread
 * through a module shared with the server.
 *
 * The codec lives here rather than in the server because it is **shared**: both sides have to read
 * it the same way, and a module both of them see is the only place where that claim is checked by
 * the compiler.
 */
dependencies {
    api(project(":booblik-core"))
    // Same reason as in the core: `follow()` returns a `Flow`, `send` returns a
    // `CompletableDeferred`, and both constructors take a `CoroutineScope`.
    api(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}
