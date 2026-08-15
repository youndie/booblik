// A build of its own, like `dev/`, and not a module of the broker's.
//
// The point of this client is that a Java service gets **one jar and nothing else** — no
// kotlin-stdlib, no coroutines. Building it inside the broker's Gradle build would make that easy
// to lose by accident: a `project(":booblik-client")` dependency is one line away, and the whole
// argument for writing this in Java rather than wrapping the Kotlin client is that the line must
// not be there.
rootProject.name = "booblik-java"
