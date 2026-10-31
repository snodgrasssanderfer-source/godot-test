package io.deepseekharness.mobile.companion.eval

import io.deepseekharness.mobile.companion.CompanionContext
import io.deepseekharness.mobile.companion.CompanionPrompt
import io.deepseekharness.mobile.companion.GeminiWire
import io.deepseekharness.mobile.companion.PersonaVoicePrompt
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/** One JSON request and response per line; no Android, network, clock reads or persona rewriting. */
object PersonaPromptBridge {
    fun handle(input: JSONObject): JSONObject = when (input.optString("op", "prompt")) {
        "prompt" -> prompt(input)
        "visible" -> {
            val raw = input.optJSONArray("parts")?.let(GeminiWire::text) ?: input.getString("text")
            val (text, trimmed) = GeminiWire.visible(raw)
            JSONObject().put("text", text).put("trimmed", trimmed)
        }
        "response" -> {
            val aggregate = GeminiWire.Accumulator()
            val responses = input.getJSONArray("responses")
            for (i in 0 until responses.length()) aggregate.accept(responses.getJSONObject(i))
            val raw = GeminiWire.text(aggregate.parts)
            val (text, trimmed) = GeminiWire.visible(raw)
            JSONObject().put("text", text).put("rawText", raw).put("trimmed", trimmed)
                .put("parts", aggregate.parts).put("finish", aggregate.finish)
                .put("continuation", aggregate.continuation ?: JSONObject.NULL).put("usage", aggregate.usage)
                .put("toolCalls", JSONArray(GeminiWire.calls(aggregate.parts)))
        }
        else -> error("unsupported operation")
    }

    private fun prompt(input: JSONObject): JSONObject {
        val persona = input.getJSONObject("persona")
        require(persona.optString("text").isNotBlank())
        val messages = input.getJSONArray("messages")
        require(messages.length() > 0 && messages.getJSONObject(messages.length() - 1).getString("role") == "user")
        val context = input.getJSONObject("context")
        val scope = context.optString("scope", "owner:local")
        val owner = scope == "owner:local"
        val authenticatedOwner = context.optBoolean("authenticatedOwner")
        // As in CompanionEngine, authenticatedOwner means the principal of an owner-only chat;
        // ownerAsked marks an authenticated owner speaking into a group.
        require(!scope.startsWith("qq:group:") || !authenticatedOwner)
        val model = context.getString("model")
        val profile = input.optJSONObject("profile") ?: JSONObject()
        if (!profile.has("maxOutputTokens")) profile.put("maxOutputTokens", 32768)
        if (!profile.has("contextBudget")) profile.put("contextBudget", 65536)
        if (!profile.has("thinking")) profile.put("thinking", "low")
        profile.put("model", model).put("tools", false)
        val budget = profile.getInt("contextBudget") - profile.getInt("maxOutputTokens")
        require(budget >= 4096)
        val suppliedMemories = context.optJSONArray("memories") ?: JSONArray()
        if (!owner && !authenticatedOwner) {
            for (i in 0 until suppliedMemories.length()) require(suppliedMemories.getJSONObject(i).optString("scope") == scope)
        }
        val memories = CompanionContext.memories(suppliedMemories, (budget / 4).coerceAtMost(6000))
        val shelf = context.optJSONArray("shelf")?.let { a -> (0 until a.length()).map(a::getJSONObject) }.orEmpty().take(60)
        val system = CompanionPrompt.system(persona, CompanionPrompt.SystemInput(
            scope, owner, authenticatedOwner, context.optString("ownerQqId"), context.optBoolean("ownerAsked"),
            context.optBoolean("onebot", scope.startsWith("qq:")), shelf, context.optString("learned"),
        ))
        val recent = (0 until messages.length()).map(messages::getJSONObject)
        val lastModel = recent.dropLast(1).lastOrNull { it.optString("role") == "assistant" }?.optString("source")
            ?.removePrefix("模型 ")?.takeIf { it.startsWith("gemini") }
        val situation = CompanionPrompt.situation(CompanionPrompt.SituationInput(
            now = LocalDateTime.parse(context.getString("time")), model = model,
            activeModel = context.optString("activeModel", model), lastModel = lastModel,
            viaClaude = context.optBoolean("viaClaude"), fallbackReason = context.optString("fallbackReason"),
            ownersOwnChat = owner || authenticatedOwner, device = context.optJSONObject("device") ?: JSONObject(),
            deviceNotes = context.optString("deviceNotes"), emergencyHeld = context.optBoolean("emergencyHeld"),
            mentionPendingReview = context.optBoolean("mentionPendingReview"), pendingReviewCount = context.optInt("pendingReviewCount"),
            people = context.optString("people"), lore = PersonaVoicePrompt.lore(persona, recent.takeLast(5).map { it.optString("text") }),
            memories = memories, failed = context.optString("failed"), jobs = context.optString("jobs"),
        ))
        val selection = CompanionPrompt.history(messages, input.optString("fingerprint", "persona-eval:$model"), budget, system, situation)
        val contents = selection.contents
        val observation = context.optJSONObject("observation") ?: context.optJSONObject("observed")
        if (observation != null) {
            val parts = observation.optJSONArray("parts") ?: JSONArray().put(JSONObject().put("text", observation.optString("text")))
            CompanionPrompt.observation(contents, observation, parts)
        }
        val cardReminder = PersonaVoicePrompt.extras(persona).optJSONObject("depthPrompt")?.optString("prompt").orEmpty()
        val explicitReminder = input.optString("voiceReminder")
        val reminder = cardReminder.takeIf { it.isNotBlank() } ?: explicitReminder.takeIf { it.isNotBlank() } ?: PersonaVoicePrompt.GENERIC
        val voiceMode = if (cardReminder.isNotBlank()) "card_depth_prompt" else if (explicitReminder.isNotBlank()) "frozen_cached_reminder" else "generic_cold_start"
        PersonaVoicePrompt.inject(contents, reminder, PersonaVoicePrompt.depth(persona), PersonaVoicePrompt.postHistory(persona))
        return JSONObject().put("system", system).put("situation", situation).put("contents", contents)
            .put("selectedMessageIds", JSONArray(selection.messageIds)).put("voiceMode", voiceMode)
            .put("request", GeminiWire.request(profile, contents, system, JSONArray()))
    }
}

fun main() {
    System.`in`.bufferedReader(Charsets.UTF_8).forEachLine { line ->
        val result = try { PersonaPromptBridge.handle(JSONObject(line)) }
        catch (failure: Exception) {
            // Exceptions can echo input JSON; the private payload must not reach a console or CI log.
            JSONObject().put("error", "prompt_bridge_failed").put("type", failure.javaClass.simpleName)
        }
        println(result.toString())
        System.out.flush()
    }
}
