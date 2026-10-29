package io.deepseekharness.mobile.companion

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** TLS for the owner's own NapCat server. A CA-issued certificate works as is; a self-signed one is trusted after the owner
 * confirms its SHA-256 fingerprint once (same value as `openssl x509 -noout -fingerprint -sha256`), and is pinned from then on.
 */
object PinnedTls {
    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    class Trust(private val pin: String) : X509TrustManager {
        private val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        /** The certificate the server presented last, so the owner can be asked about it. */
        @Volatile var presented = ""; private set
        @Volatile var pinned = false; private set

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            presented = fingerprint(chain[0])
            if (pin.isNotBlank() && presented.equals(pin, ignoreCase = true)) { pinned = true; return }
            system.checkServerTrusted(chain, authType)
        }
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    fun client(base: OkHttpClient, trust: Trust): OkHttpClient {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val defaultHosts = HttpsURLConnection.getDefaultHostnameVerifier()
        // A pinned self-signed certificate often names an IP or nothing useful; the pin itself is the identity check.
        return base.newBuilder().sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier { host, session -> trust.pinned || defaultHosts.verify(host, session) }.build()
    }
}
