package io.deepseekharness.mobile.companion.social

import io.deepseekharness.mobile.companion.iteration.CommandPrincipal
import io.deepseekharness.mobile.companion.iteration.HostPorts
import io.deepseekharness.mobile.companion.iteration.ModuleEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SocialSyncTest {
    private val ports = object:HostPorts {
        override fun emit(topic:String,payload:JSONObject) {}
        override fun requestModel(request:JSONObject,onEvent:(JSONObject)->Unit):String = error("sync must not request a model")
        override fun cancelModel(requestId:String) {}
        override fun authorizeEffect(effect:JSONObject,principal:CommandPrincipal)=JSONObject().put("allowed",false)
    }
    private fun setup():Pair<SocialLedger,SocialRuntime> {
        val ledger=SocialLedger();ledger.change { rows(it,"sync").put("vault","0".repeat(32)) }
        val runtime=SocialRuntime(ledger,ports,SocialTransport { _,_,_->error("sync must not send social actions") })
        runtime.onEvent(ModuleEvent("model.completed","owner:local","source-one",JSONObject().put("text","private synthetic memory")))
        return ledger to runtime
    }
    private fun response(request:JSONObject)=JSONObject().put("version",1).put("generation",0).put("cursor",0)
        .put("events",JSONArray()).put("deletions",JSONArray()).put("ackDeletions",request.getJSONArray("deletions")).put("ackIds",JSONArray())
    @Test fun authenticatedCipherRejectsTamperedSourceMetadata() {
        val cipher=SyncCipher(ByteArray(32){it.toByte()});val event=JSONObject().put("id","event").put("sourceId","source").put("text","secret")
        val sealed=cipher.seal(event);assertFalse(sealed.toString().contains("secret"));assertEquals("secret",cipher.open(sealed).getString("text"))
        sealed.put("sources",JSONArray().put("0".repeat(64)))
        assertThrows(Exception::class.java) { cipher.open(sealed) }
    }
    @Test fun uploadRequiresDurableExactAckAndCiphertextIsStableAcrossRetries() {
        val (ledger,runtime)=setup();val requests=mutableListOf<JSONObject>();var acknowledge=false
        val sync=SocialSync(runtime,SyncCipher(ByteArray(32){1}),SyncTransport { request -> requests.add(copy(request));response(request).also { r ->
            if(acknowledge)r.put("ackIds",JSONArray(objects(request.getJSONArray("events")).map {it.getString("id")}))
        } })
        sync.run();val first=requests[1].getJSONArray("events").getJSONObject(0);assertTrue(ledger.read { values(rows(it,"events")).single().getBoolean("pendingSync") })
        acknowledge=true;sync.run();assertEquals(first.toString(),requests[3].getJSONArray("events").getJSONObject(0).toString())
        assertFalse(ledger.read { values(rows(it,"events")).single().getBoolean("pendingSync") });assertEquals(0,requests[0].getJSONArray("events").length())
    }
    @Test fun reconnectAppliesDeletionBeforeAnyOldContentUpload() {
        val (ledger,runtime)=setup();val requests=mutableListOf<JSONObject>()
        SocialSync(runtime,SyncCipher(ByteArray(32){2}),SyncTransport { req -> requests.add(copy(req));response(req).put("generation",1).put("deletions",JSONArray().put(hash("source-one"))) }).run()
        assertEquals(0,requests[1].getJSONArray("events").length());assertEquals(0,ledger.read { rows(it,"events").length() })
    }
    @Test fun unknownAckOrConnectionLossLeavesOutboxAndDoesNotRepeatPaidWork() {
        val (ledger,runtime)=setup();var n=0
        val sync=SocialSync(runtime,SyncCipher(ByteArray(32){3}),SyncTransport { req -> n++;if(n==2)response(req).put("ackIds",JSONArray().put("unrelated")) else response(req) })
        assertThrows(IllegalStateException::class.java) { sync.run() }
        assertTrue(ledger.read { values(rows(it,"events")).single().getBoolean("pendingSync") })
        assertThrows(IllegalStateException::class.java) { SocialSync(runtime,SyncCipher(ByteArray(32){3}),SyncTransport { throw IllegalStateException("offline") }).run() }
    }
    @Test fun endpointDisallowsCleartextCredentialsAndQuery() {
        listOf("http://127.0.0.1","https://user:secret@example.test","https://example.test?token=secret").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { HttpsSyncTransport.validatedEndpoint(value) }
        }
    }
    @Test fun multimodalSegmentsPreserveUnresolvedTypesAndBoundNestedForwarding() {
        val data=JSONArray().put(JSONObject().put("type","record").put("data",JSONObject().put("url","https://invalid.test/a")))
            .put(JSONObject().put("type","video").put("data",JSONObject())).put(JSONObject().put("type","forward").put("data",JSONObject().put("id","forward-id")))
        val parsed=OneBotSegments.parse(data,"10001");assertEquals(3,parsed.getJSONArray("attachments").length());assertTrue(parsed.getString("text").contains("待理解"))
        var nested=JSONArray().put(JSONObject().put("type","text").put("data",JSONObject().put("text","deep")))
        repeat(10) { nested=JSONArray().put(JSONObject().put("type","node").put("data",JSONObject().put("content",nested))) }
        assertTrue(OneBotSegments.parse(nested,"10001").getBoolean("truncated"))
    }
}
