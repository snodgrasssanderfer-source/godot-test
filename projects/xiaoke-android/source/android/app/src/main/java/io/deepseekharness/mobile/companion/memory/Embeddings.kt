package io.deepseekharness.mobile.companion.memory

import android.content.Context
import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.CompanionSecrets
import io.deepseekharness.mobile.companion.CompanionStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

interface Embedder {
    /** Vectors from different embedders never meet; the tag keeps them apart in the store. */
    val tag: String
    fun embed(texts: List<String>, query: Boolean): List<FloatArray>
}

class EmbeddingUnsupported(message: String) : Exception(message)

/** Gemini embeddings through the same relay and key as chat; the text already goes to Gemini, so nothing new leaves the phone. */
class GeminiEmbedder(private val settings: JSONObject, private val readKey: () -> String, private val model: String) : Embedder {
    override val tag = "gemini:$model:$DIMENSIONS"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    override fun embed(texts: List<String>, query: Boolean): List<FloatArray> {
        check(settings.optBoolean("networkEnabled") && !settings.optBoolean("paused")) { "联网已关闭" }
        val base = CompanionPolicy.endpoint(settings.getString("endpoint"))
        val requests = JSONArray()
        texts.forEach { text ->
            requests.put(JSONObject().put("model", "models/$model").put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text.take(6000)))))
                .put("taskType", if (query) "RETRIEVAL_QUERY" else "RETRIEVAL_DOCUMENT").put("outputDimensionality", DIMENSIONS))
        }
        val request = Request.Builder().url("$base/models/$model:batchEmbedContents").header("x-goog-api-key", readKey())
            .post(JSONObject().put("requests", requests).toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            // A relay without the embedding route answers 404/405/400/501; anything else may be temporary.
            if (response.code in setOf(400, 404, 405, 501)) throw EmbeddingUnsupported("中转不支持向量接口（${response.code}）")
            check(response.isSuccessful) { "向量接口暂时不可用（${response.code}）" }
            val list = JSONObject(body).optJSONArray("embeddings") ?: throw EmbeddingUnsupported("中转返回的不是向量")
            check(list.length() == texts.size) { "向量数量不符" }
            return (0 until list.length()).map { i ->
                val values = list.getJSONObject(i).getJSONArray("values")
                normalize(FloatArray(values.length()) { values.getDouble(it).toFloat() })
            }
        }
    }

    companion object { const val DIMENSIONS = 768 }
}

/** bge-small-zh-v1.5 on the phone (ONNX Runtime): CLS pooling, normalized. Used when the relay has no embedding route. */
class LocalEmbedder private constructor(private val tokenizer: WordPiece, private val session: ai.onnxruntime.OrtSession) : Embedder {
    override val tag = "bge-small-zh-v1.5"

    override fun embed(texts: List<String>, query: Boolean): List<FloatArray> = synchronized(session) {
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        texts.map { text ->
            // bge-zh's retrieval instruction goes on queries only.
            val ids = tokenizer.encode((if (query) QUERY_PREFIX else "") + text, 256).map { it.toLong() }.toLongArray()
            val shape = longArrayOf(1, ids.size.toLong())
            val inputs = mapOf(
                "input_ids" to ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(ids), shape),
                "attention_mask" to ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(LongArray(ids.size) { 1 }), shape),
                "token_type_ids" to ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(LongArray(ids.size)), shape),
            )
            try {
                session.run(inputs).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val hidden = (result[0].value as Array<Array<FloatArray>>)[0][0]
                    normalize(hidden.copyOf())
                }
            } finally { inputs.values.forEach { it.close() } }
        }
    }

    companion object {
        const val QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
        const val MODEL_ASSET = "embedding/bge-small-zh-v1.5.onnx"
        const val VOCAB_ASSET = "embedding/vocab.txt"
        @Volatile private var loaded: LocalEmbedder? = null
        fun available(context: Context) = runCatching { context.assets.open(MODEL_ASSET).close(); true }.getOrDefault(false)
        fun get(context: Context): LocalEmbedder = loaded ?: synchronized(this) {
            loaded ?: run {
                val vocab = context.assets.open(VOCAB_ASSET).bufferedReader(Charsets.UTF_8).readLines()
                val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
                val options = ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
                LocalEmbedder(WordPiece(vocab), ai.onnxruntime.OrtEnvironment.getEnvironment().createSession(bytes, options))
            }.also { loaded = it }
        }
    }
}

fun normalize(v: FloatArray): FloatArray {
    var sum = 0.0
    for (x in v) sum += x * x
    val n = sqrt(sum).toFloat().takeIf { it > 0f } ?: return v
    for (i in v.indices) v[i] /= n
    return v
}

fun cosine(a: FloatArray, b: FloatArray): Double {
    if (a.size != b.size) return 0.0
    var dot = 0.0
    for (i in a.indices) dot += a[i] * b[i]
    return dot
}

/**
 * Which embedder is in use. Settings `embedding`: auto (default: relay first, local model if the relay has no
 * embedding route), remote, local, off. The relay verdict is remembered for a week so a missing route costs one probe.
 */
class Embeddings(private val context: Context, private val store: CompanionStore, private val secrets: CompanionSecrets) {
    fun active(): Embedder? {
        val settings = store.settings()
        val mode = settings.optString("embedding", "auto")
        val model = settings.optString("embeddingModel", "gemini-embedding-001")
        val remote = GeminiEmbedder(settings, secrets::read, model)
        val local = { if (LocalEmbedder.available(context)) runCatching { LocalEmbedder.get(context) }.getOrNull() else null }
        return when (mode) {
            "off" -> null
            "local" -> local()
            "remote" -> remote
            else -> {
                val verdict = store.lifeState("embedding")
                val unsupported = verdict.optString("model") == model && verdict.optString("relay") == settings.optString("endpoint") &&
                    verdict.optBoolean("unsupported") && System.currentTimeMillis() - verdict.optLong("checkedAt") < 7 * 86_400_000L
                if (unsupported || !settings.optBoolean("networkEnabled")) local() ?: remote.takeIf { !unsupported } else remote
            }
        }
    }

    /** Embeds with the active embedder; a relay that turns out to lack the route is remembered and the local model takes over. */
    fun embed(texts: List<String>, query: Boolean): Pair<String, List<FloatArray>>? {
        if (texts.isEmpty()) return null
        val embedder = active() ?: return null
        return try { embedder.tag to embedder.embed(texts, query) }
        catch (e: EmbeddingUnsupported) {
            val settings = store.settings()
            store.saveLifeState("embedding", JSONObject().put("unsupported", true).put("checkedAt", System.currentTimeMillis())
                .put("model", settings.optString("embeddingModel", "gemini-embedding-001")).put("relay", settings.optString("endpoint")).put("reason", e.message))
            val local = if (LocalEmbedder.available(context)) runCatching { LocalEmbedder.get(context) }.getOrNull() else null
            local?.let { it.tag to it.embed(texts, query) }
        }
    }
}
