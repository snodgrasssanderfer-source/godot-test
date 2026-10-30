package io.deepseekharness.mobile.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The Capacitor plugin's load() builds the engine (opening the database) and every module; an exception there is what
 * the app shows as "Companion plugin is not implemented". This caught `PRAGMA secure_delete` going through execSQL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Conscrypt would be installed as the JVM-wide first security provider and break the plain-JDK TLS tests that run after this one.
@ConscryptMode(ConscryptMode.Mode.OFF)
class CompanionStartupTest {
    @Test fun `引擎和全部模块能在新装的手机上建起来`() {
        FakeAndroidKeyStore.install()
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            val engine = CompanionEngine.get(context)
            assertNotNull(engine.store.settings())
            assertNotNull(engine.modules.router)
        } catch (e: Throwable) {
            // Stack traces cannot be formatted inside the sandbox on this JDK; carry the cause chain as text.
            throw AssertionError(generateSequence(e) { it.cause }.joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" })
        } finally { java.security.Security.removeProvider("AndroidKeyStore") }
    }
}
