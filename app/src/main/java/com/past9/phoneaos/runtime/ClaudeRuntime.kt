package com.past9.phoneaos.runtime

import android.content.Context
import com.past9.phoneaos.ui.screens.RuntimeInfo
import java.io.File

/**
 * The Claude-subscription path. Node ships as a native library (lib/arm64-v8a/libnode.so) so
 * Android lets us execute it from nativeLibraryDir; everything else (Claude Code, the Agent SDK,
 * our bridge script) is JavaScript unpacked into filesDir/runtime, which we can delete at will.
 */
class ClaudeRuntime(private val context: Context) {
    val node: File get() = File(context.applicationInfo.nativeLibraryDir, "libnode.so")
    val dir: File get() = File(context.filesDir, "runtime")
    val home: File get() = File(dir, "home")

    val available: Boolean get() = node.exists()
    val installed: Boolean get() = File(dir, "node_modules/@anthropic-ai/claude-agent-sdk/package.json").exists()
    val signedIn: Boolean get() = tokenFile.exists() && tokenFile.length() > 10
    private val tokenFile: File get() = File(home, ".claude-oauth-token")

    fun sizeBytes(): Long = if (dir.exists()) dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L

    fun info(): RuntimeInfo = RuntimeInfo(
        available = available, installedBytes = sizeBytes(), signedIn = signedIn,
        note = when {
            !available -> "Runtime pack not in this build"
            !installed -> "Ready to set up"
            !signedIn -> "Installed · sign in with Claude"
            else -> "Ready · signed in"
        },
    )

    /** Everything we unpacked or downloaded. The native libs inside the APK can't be deleted. */
    fun delete() { dir.deleteRecursively() }
}
