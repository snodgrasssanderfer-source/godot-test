package io.deepseekharness.mobile.companion.claude

import android.content.Context
import io.deepseekharness.mobile.companion.CompanionSecrets

/**
 * The owner's GitHub token, so the owner's own Claude turns can push a branch and open a PR from the phone workspace.
 *
 * Stored with the same Keystore-backed [CompanionSecrets] as the Gemini key. It reaches only the processes of the
 * owner's turns and workspace fetches, and only through stdin: launches start from `env -i`, so a host environment
 * variable would never get through, and a file or argv entry would land in the rootfs or the process list. A guest
 * wrapper ([STDIN_EXPORT]) reads the first stdin line into GH_TOKEN, sets git's credential helper through
 * GIT_CONFIG_* variables (no ~/.gitconfig, no ~/.git-credentials) and execs the real command, which keeps reading the
 * rest of stdin. It is gone when the process ends.
 */
object ClaudeGithub {
    private fun secrets(context: Context) = CompanionSecrets(context, "github")
    fun configured(context: Context) = secrets(context).configured()
    fun token(context: Context): String? = runCatching { secrets(context).read() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun save(context: Context, token: String) {
        val value = token.trim()
        require(Regex("[A-Za-z0-9_]{20,255}").matches(value)) { "这不像 GitHub token（github_pat_… 或 ghp_…）" }
        secrets(context).save(value)
    }
    fun clear(context: Context) = secrets(context).clear()

    /**
     * Bash, run before `exec "$@"`: bash's `read` takes stdin a byte at a time from a pipe, so it consumes exactly the
     * token line and leaves the rest (Claude Code's stream-json) for the command it execs.
     */
    const val STDIN_EXPORT = "IFS= read -r GH_TOKEN || exit 4; " +
        "export GH_TOKEN GIT_TERMINAL_PROMPT=0 GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=credential.https://github.com.helper; " +
        "export GIT_CONFIG_VALUE_0='!f() { echo username=x-access-token; echo \"password=\$GH_TOKEN\"; }; f'; "

    private val patterns = listOf(Regex("github_pat_[A-Za-z0-9_]{20,}"), Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}"))

    /** The token itself, and anything shaped like a GitHub token, becomes "***" before it is shown, saved or logged. */
    fun redact(text: String, token: String?): String {
        var out = if (!token.isNullOrBlank()) text.replace(token, "***") else text
        patterns.forEach { out = it.replace(out, "***") }
        return out
    }
}
