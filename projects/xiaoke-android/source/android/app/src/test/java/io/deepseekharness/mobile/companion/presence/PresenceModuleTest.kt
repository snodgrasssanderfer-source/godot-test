package io.deepseekharness.mobile.companion.presence

import org.junit.Test

/** Runs the same dependency-free fixtures under the host's existing JUnit dependency. */
class PresenceModuleTest {
    @Test fun sessionTransitions() { PresenceCoreTest.main(emptyArray()) }
    @Test fun callbackRaces() { PresenceControllerTest.main(emptyArray()) }
    @Test fun audioEnvelope() { WavEnvelopeTest.main(emptyArray()) }
    @Test fun streamingText() { PresenceTextStreamTest.main(emptyArray()) }
}
