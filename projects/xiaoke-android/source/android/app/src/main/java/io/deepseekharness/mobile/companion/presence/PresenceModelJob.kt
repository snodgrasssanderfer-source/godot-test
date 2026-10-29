package io.deepseekharness.mobile.companion.presence

import org.json.JSONArray

/** Ephemeral work sent to CompanionEngine's existing serialized worker; never stored as a chat task. */
data class PresenceModelJob(
    val id: String,
    val contents: JSONArray,
    val system: String,
    val isCurrent: () -> Boolean,
    val onPartial: (String) -> Unit,
    val onComplete: (String) -> Unit,
    val onFailure: () -> Unit,
)
