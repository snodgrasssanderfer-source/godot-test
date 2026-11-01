package io.deepseekharness.mobile.companion

/** JVM-only adapters; the benchmark supplies the secret reader directly and never logs payloads. */
class CompanionSecrets { fun read(): String = error("Android secret storage is unavailable in the benchmark") }
object XLog { @Suppress("UNUSED_PARAMETER") fun w(message: String) {} }
