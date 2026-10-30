package io.deepseekharness.mobile.companion

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Robolectric has no AndroidKeyStore; this in-memory stand-in keeps AES keys by alias so startup code can run. */
object FakeAndroidKeyStore {
    val keys = HashMap<String, Key>()
    @Volatile private var lastAlias = "default"

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?) = keys[alias]
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String) = Date()
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) { keys[alias] = key }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)
        override fun engineContainsAlias(alias: String) = keys.containsKey(alias)
        override fun engineSize() = keys.size
        override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) {}
        override fun engineLoad(stream: InputStream?, password: CharArray?) {}
    }

    class AesGenerator : KeyGeneratorSpi() {
        override fun engineInit(random: SecureRandom?) {}
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            // KeyGenParameterSpec.getKeystoreAlias() via reflection keeps this file free of android.security imports at class load.
            lastAlias = runCatching { params!!.javaClass.getMethod("getKeystoreAlias").invoke(params) as String }.getOrDefault("default")
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) {}
        override fun engineGenerateKey(): SecretKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES").also { keys[lastAlias] = it }
    }

    fun install() {
        if (Security.getProvider("AndroidKeyStore") != null) return
        Security.addProvider(object : Provider("AndroidKeyStore", 1.0, "test stand-in") {
            init {
                put("KeyStore.AndroidKeyStore", Store::class.java.name)
                put("KeyGenerator.AES", AesGenerator::class.java.name)
            }
        })
    }
}
