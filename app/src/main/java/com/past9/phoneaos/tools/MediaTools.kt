package com.past9.phoneaos.tools

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Base64
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.bool
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.postJson
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.sharedHttp
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.camera.CameraPhoto
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.machines.MachineService
import com.past9.phoneaos.machines.Ssh
import com.past9.phoneaos.share.ShareKind
import com.past9.phoneaos.share.ShareLogic
import com.past9.phoneaos.share.ShareStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where the media tools may read: the app's own storage and the shared storage root (same as send_file). */
private fun mediaRoots(ctx: Context) = listOf(ctx.filesDir, ctx.cacheDir, android.os.Environment.getExternalStorageDirectory())

private fun resolve(ctx: Context, path: String): Pair<File?, String?> {
    val f = runCatching { File(path).canonicalFile }.getOrNull() ?: return null to "That is not a file path."
    if (!f.isFile) return null to "No such file on the phone: $path"
    if (mediaRoots(ctx).none { f.path.startsWith(it.canonicalPath) }) return null to "That file is outside the app's storage and the phone's shared storage."
    return f to null
}

/** Pulls a few evenly spaced frames out of a video on the phone, no extra libraries. */
class MediaFramesTool(private val context: Context) : Tool {
    override val spec = ToolSpec("media_frames",
        "See a video you cannot play: pulls a few evenly spaced key frames out of it on the phone and shows them to you as images, with the duration and size of the video. Use it for videos the user shared (the chat message gives the path). Pair it with media_transcribe to also hear what is said. For an audio file it only gives the duration.",
        schema(listOf("path"), "path" to str("Path of the video on the phone"), "count" to int("How many frames, 1 to ${ShareLogic.MAX_FRAMES} (default 5)")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val (f, err) = resolve(context, input.optString("path")); if (f == null) return err!!
        val count = input.optInt("count", 5).coerceIn(1, ShareLogic.MAX_FRAMES)
        return withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.path)
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                if (!hasVideo) return@withContext "${f.name} has no picture, it is audio only (${ShareLogic.formatDuration(dur)} long). Use media_transcribe to hear it."
                var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                if ((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) % 180 != 0) { val t = w; w = h; h = t }
                val stamp = System.currentTimeMillis()
                val lines = ShareLogic.frameTimesUs(dur, count).mapIndexedNotNull { i, us ->
                    val bmp = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@mapIndexedNotNull null
                    val (tw, th) = CameraPhoto.scaledSize(bmp.width, bmp.height, 1024)
                    val out = File(context.filesDir, "uploads/frame-$stamp-$i.jpg").apply { parentFile?.mkdirs() }
                    out.outputStream().use { (if (tw == bmp.width) bmp else Bitmap.createScaledBitmap(bmp, tw, th, true)).compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    "At ${ShareLogic.formatDuration(us / 1000)}: [[image:${out.path}]]"
                }
                if (lines.isEmpty()) return@withContext "Could not read any frames from ${f.name}. The video may be damaged or in a format this phone cannot decode."
                "Video ${f.name}: ${ShareLogic.formatDuration(dur)} long, ${w}x$h, ${ShareLogic.formatSize(f.length())}. ${lines.size} frame${if (lines.size == 1) "" else "s"}:\n" + lines.joinToString("\n")
            } catch (e: Exception) {
                "Could not open ${f.name} as a video: ${e.message ?: e.javaClass.simpleName}"
            } finally { runCatching { r.release() } }
        }
    }
}

/** Turns speech in an audio file or a video's sound into text: Gemini from the phone, else a connected machine. */
class MediaTranscribeTool(private val context: Context, private val settings: SettingsStore, private val machines: MachineService) : Tool {
    override val spec = ToolSpec("media_transcribe",
        "Hear an audio file or a video: turns the speech in a voice note (WhatsApp .opus/.ogg, .m4a, .mp3, .amr...) or a video's sound track into text, word for word, with no translation (isiZulu and English mixed is fine). Use it for voice notes and videos the user shared (the chat message gives the path). It uses Gemini from the phone when a Gemini key is set up, otherwise a connected machine (Groq Whisper or whisper). It says which it used and what failed. On mobile data it will NOT upload a video or a file over 15 MB: tell the user, and call again with allow_mobile_data true only after they say yes.",
        schema(listOf("path"),
            "path" to str("Path of the audio or video file on the phone"),
            "machine" to str("Optional: transcribe on this connected machine instead of from the phone"),
            "allow_mobile_data" to bool("Only true after the user said it is fine to upload this over mobile data"),
            "language_hint" to str("Optional: what language(s) to expect, e.g. 'English and isiZulu'")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val (f, err) = resolve(context, input.optString("path")); if (f == null) return err!!
        val kind = ShareLogic.kindOf(ShareLogic.mimeFor(null, f.name), f.name)
        if (kind != ShareKind.AUDIO && kind != ShareKind.VIDEO) return "${f.name} is not audio or video, so there is nothing to transcribe."
        val allow = input.optBoolean("allow_mobile_data")
        val metered = ShareStore.isMetered(context)
        val wantMachine = input.optString("machine").takeIf { it.isNotBlank() }
        val notes = mutableListOf<String>()
        val key = settings.voiceKey("gemini")?.takeIf { it.isNotBlank() }

        if (wantMachine == null) {
            if (key == null) notes += "No Gemini key is set up on the phone."
            else {
                val payload = withContext(Dispatchers.IO) { payloadFor(f, kind) }
                if (payload == null) notes += "Gemini: could not get the sound out of this file on the phone."
                else {
                    val (pf, mime, temp) = payload
                    try {
                        if (pf.length() > INLINE_MAX) notes += "Gemini: the audio is ${ShareLogic.formatSize(pf.length())}, too big to send in one go."
                        else if (!allow && ShareLogic.holdForWifi(pf.length(), false, metered))
                            return HOLD + " It is ${ShareLogic.formatSize(pf.length())} of audio. Ask the user, or wait for Wi-Fi."
                        else {
                            ctx.activity("Listening to ${f.name}", JSONObject().put("tool", "media"))
                            val r = runCatching { gemini(key, pf, mime, input.optString("language_hint")) }
                            r.getOrNull()?.let { return "Transcript of ${f.name} (${ShareLogic.formatDuration(ShareStore.durationMs(f.path))}), by Gemini on the phone:\n$it" }
                            notes += "Gemini failed: ${r.exceptionOrNull()?.message?.take(200)}"
                        }
                    } finally { if (temp) pf.delete() }
                }
            }
        }

        // Fallback: a connected machine does it.
        val ms = machines.store.machines.value
        val m = if (wantMachine != null) machines.store.find(wantMachine) else ms.firstOrNull()
        if (m == null) {
            return (if (wantMachine != null) "No machine called \"$wantMachine\". " else "") + notes.joinToString(" ") +
                (if (ms.isEmpty()) " No machine is connected to fall back on (the user can add one in Connections > Machines, or add a Gemini key in the voice settings)." else "")
        }
        if (!allow && ShareLogic.holdForWifi(f.length(), kind == ShareKind.VIDEO, metered))
            return HOLD + " ${f.name} is ${if (kind == ShareKind.VIDEO) "a video" else "big"} (${ShareLogic.formatSize(f.length())}), and sending it to ${m.name} would use their mobile data. Ask the user, or wait for Wi-Fi." +
                (if (notes.isNotEmpty()) " (" + notes.joinToString(" ") + ")" else "")
        if (!m.trusted) {
            val a = ctx.ask("APPROVAL|Upload a file to transcribe on ${m.name}|${f.name} (${ShareLogic.formatSize(f.length())})", listOf("Approve", "Decline"))
            if (a != "Approve") return (if (a == null) "Needs the user's approval and nobody is around. Not done." else "The user declined. Not done.") + " " + notes.joinToString(" ")
        }
        ctx.activity("Sending ${f.name} to ${m.name} to transcribe", JSONObject().put("tool", "media"))
        val r = runCatching {
            machines.with(m.id) { _, s ->
                val home = Ssh.run(s, "mkdir -p \"\$HOME/.agent-media\" && printf %s \"\$HOME\"", 30).output.trim()
                val remote = "$home/.agent-media/${ShareLogic.safeName(f.name)}"
                Ssh.upload(s, f, remote)
                val out = Ssh.run(s, "bash -s", 330, maxChars = 40_000, stdin = machineScript(remote))
                Ssh.run(s, "rm -f '$remote' '$remote.16k.mp3'", 20)
                out
            }
        }
        val out = r.getOrNull() ?: return "Machine ${m.name} failed: ${r.exceptionOrNull()?.message?.take(300)}. " + notes.joinToString(" ")
        val text = out.output.trim()
        return if (out.exit == 0 && text.isNotBlank()) "Transcript of ${f.name}, from ${m.name}:\n" + text.replace(Regex("\\[\\[via ([^\\]]+)]]"), "(via $1)")
        else "Transcribing on ${m.name} did not work: ${text.takeLast(500).ifBlank { "no output" }}. " + notes.joinToString(" ")
    }

    private fun payloadFor(f: File, kind: ShareKind): Triple<File, String, Boolean>? {
        if (kind == ShareKind.AUDIO) {
            val mime = geminiMime(f.name) ?: return AudioExtract.toTemp(context, f)?.let { Triple(it.first, it.second, true) }
            return Triple(f, mime, false)
        }
        return AudioExtract.toTemp(context, f)?.let { Triple(it.first, it.second, true) }
    }

    private suspend fun gemini(key: String, f: File, mime: String, hint: String): String {
        val prompt = "Transcribe this recording word for word, in the language(s) spoken. " +
            (if (hint.isNotBlank()) "Expect: $hint. " else "The speaker may mix English and isiZulu (or another South African language): keep every word in the language it was spoken in. ") +
            "Do not translate, summarise, correct or comment. Label speakers if there is more than one. Output only the transcript. If nobody speaks, reply exactly: No speech heard."
        val data = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", data)))
            .put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0))
        val res = postJson(sharedHttp, "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent", mapOf("x-goog-api-key" to key), body, attempts = 2)
        val parts = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        val text = (0 until (parts?.length() ?: 0)).joinToString("") { parts!!.getJSONObject(it).optString("text") }.trim()
        if (text.isEmpty()) throw IllegalStateException("Gemini sent back no text")
        return text
    }

    companion object {
        const val GEMINI_MODEL = "gemini-flash-latest"
        const val INLINE_MAX = 14L * 1024 * 1024
        const val HOLD = "Not uploaded: the phone is on mobile data."

        /** Gemini takes wav, mp3, aiff, aac, ogg (incl. opus), flac and mp4 audio. Null when it needs converting. */
        fun geminiMime(name: String): String? = when (ShareLogic.extension(name)) {
            "ogg", "oga", "opus" -> "audio/ogg"; "mp3" -> "audio/mp3"; "wav" -> "audio/wav"; "flac" -> "audio/flac"; "aac" -> "audio/aac"; "m4a" -> "audio/mp4"
            else -> null
        }

        /** What runs on the other machine: Groq Whisper with a key already there, else whisper. The key is never printed. */
        fun machineScript(remote: String): String = """
            f='${remote.replace("'", "'\\''")}'
            key="${'$'}{GROQ_API_KEY:-}"
            if [ -z "${'$'}key" ]; then for e in "${'$'}HOME/.env" "${'$'}HOME/.hermes/.env" "${'$'}HOME/.config/groq/env"; do
              [ -f "${'$'}e" ] || continue
              k=${'$'}(grep -E '^(export )?GROQ_API_KEY=' "${'$'}e" | tail -1 | sed -E 's/^(export )?GROQ_API_KEY=//; s/^["'"'"']//; s/["'"'"']${'$'}//')
              [ -n "${'$'}k" ] && key="${'$'}k" && break
            done; fi
            in="${'$'}f"
            if command -v ffmpeg >/dev/null 2>&1; then ffmpeg -nostdin -y -loglevel error -i "${'$'}f" -vn -ac 1 -ar 16000 -b:a 32k "${'$'}f.16k.mp3" && in="${'$'}f.16k.mp3"; fi
            if [ -n "${'$'}key" ]; then
              sz=${'$'}(stat -c %s "${'$'}in" 2>/dev/null || echo 0)
              if [ "${'$'}sz" -lt 25000000 ]; then
                if t=${'$'}(curl -sS --fail --max-time 240 https://api.groq.com/openai/v1/audio/transcriptions -H "Authorization: Bearer ${'$'}key" -F "file=@${'$'}in" -F model=whisper-large-v3 -F response_format=text 2>&1); then echo "${'$'}t"; echo "[[via Groq Whisper large-v3]]"; exit 0; fi
                echo "Groq failed: ${'$'}(echo "${'$'}t" | head -c 200)"
              else echo "Groq skipped: file over 25 MB and no ffmpeg to shrink it"; fi
            fi
            if command -v whisper >/dev/null 2>&1; then
              mkdir -p /tmp/agent-whisper && whisper "${'$'}in" --model small --output_format txt --output_dir /tmp/agent-whisper >/dev/null 2>&1
              o="/tmp/agent-whisper/${'$'}(basename "${'$'}{in%.*}").txt"
              if [ -s "${'$'}o" ]; then cat "${'$'}o"; echo "[[via whisper small]]"; exit 0; fi
            fi
            echo "No transcriber on this machine: it needs a GROQ_API_KEY (environment or ~/.env) or the whisper program."; exit 3
        """.trimIndent()
    }
}

/** Takes the sound track out of a video (or a file Gemini can't read) on the phone, without re-encoding. */
object AudioExtract {
    /** (file, mime) for the audio track copied to a temp file, or null when the codec can't be put in a container Android writes. */
    fun toTemp(ctx: Context, src: File): Pair<File, String>? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(src.path)
            val idx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val fmt = ex.getTrackFormat(idx)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val (fmtId, ext, outMime) = when (mime) {
                "audio/mp4a-latm" -> Triple(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "m4a", "audio/mp4")
                "audio/opus" -> Triple(MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, "ogg", "audio/ogg")
                else -> return null
            }
            val out = File(ctx.cacheDir, "extract-${System.nanoTime()}.$ext")
            val mux = MediaMuxer(out.path, fmtId)
            try {
                ex.selectTrack(idx)
                val t = mux.addTrack(fmt)
                mux.start()
                val buf = java.nio.ByteBuffer.allocate(1 shl 20); val info = android.media.MediaCodec.BufferInfo()
                while (true) {
                    info.size = ex.readSampleData(buf, 0); if (info.size < 0) break
                    info.presentationTimeUs = ex.sampleTime; info.flags = ex.sampleFlags; info.offset = 0
                    mux.writeSampleData(t, buf, info); ex.advance()
                }
                mux.stop()
            } finally { runCatching { mux.release() } }
            out to outMime
        } finally { ex.release() }
    }.getOrNull()
}
