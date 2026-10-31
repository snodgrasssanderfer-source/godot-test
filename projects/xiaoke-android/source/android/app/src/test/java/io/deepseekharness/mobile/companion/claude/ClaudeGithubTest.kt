package io.deepseekharness.mobile.companion.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ClaudeGithubTest {
    private val token = "github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyz"

    @Test fun `token 出现在输出里就换成星号`() {
        assertEquals("push with *** failed", ClaudeGithub.redact("push with $token failed", token))
        // Anything shaped like a GitHub token goes too, even one we were not told about.
        assertEquals("x *** y", ClaudeGithub.redact("x ghp_abcdefghijklmnopqrstuvwxyz0123 y", null))
        assertFalse(io.deepseekharness.mobile.companion.XLog.scrub("token was $token").contains("abcdefghijklmnop"))
    }

    @Test fun `只有主人回合拿到 token，群里的命令里没有`() {
        fun opts(owner: Boolean) = ClaudeTurn.Options("s", "hi", null, "default", "high", owner, cwd = "/root", mcpPort = 1, mcpToken = "t", timeoutMs = 1, githubToken = token)
        assertTrue(opts(true).github)
        assertFalse(opts(false).github)
        assertFalse(ClaudeRuntime.command("/c", listOf("-p"), "/root", github = false).any { "GH_TOKEN" in it })
        // Even when it is passed, the token itself is never part of the command line.
        assertFalse(ClaudeRuntime.command("/c", listOf("-p"), "/root", github = true).any { token in it })
    }

    @Test fun `包装脚本只读走第一行，git 能从环境里拿到凭据`() {
        assumeTrue(File("/bin/bash").canExecute() && listOf("/usr/bin/git", "/bin/git").any { File(it).canExecute() })
        val script = ClaudeGithub.STDIN_EXPORT + "exec \"\$@\""
        val p = ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-c", script, "x", "/bin/sh", "-c",
            "printf 'protocol=https\\nhost=github.com\\n\\n' | git credential fill; cat")
            .redirectErrorStream(true).also { it.environment().remove("GH_TOKEN"); it.environment()["HOME"] = System.getProperty("java.io.tmpdir") }.start()
        p.outputStream.use { it.write("$token\n{\"type\":\"user\"}\n".toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS))
        assertTrue(out, out.contains("username=x-access-token") && out.contains("password=$token"))
        // What follows the token line is left for the real command (Claude Code's stream-json).
        assertTrue(out, out.trimEnd().endsWith("{\"type\":\"user\"}"))
    }
}
