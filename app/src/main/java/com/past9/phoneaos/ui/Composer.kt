package com.past9.phoneaos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What one message box holds before it is sent: the words, the photos and the documents. The main chat has one, and
 * every project or helper chat opened on the phone has its own, so a photo picked in one never lands in another
 * (29 Oct 2026: the project chat had none, and its sends carried no pictures at all).
 */
class ComposerBox(val state: MutableState<String>, val key: String = "") {
    var draft by state
    /** Messages of this box on their way now: the words stay until the computer took them. */
    private var flying by mutableStateOf(0)
    /**
     * A message is on its way. Never a reason to stop him typing or sending: the agent being busy with one message is
     * shown as the agent working, not on the Send button (30 Oct 2026, "the send icon shows loading while the agent works
     * and blocks me from sending").
     */
    val sending: Boolean get() = flying > 0
    /** The last send that did not get through, with its words still in the box, for the Retry line under it. */
    var failed by mutableStateOf<String?>(null)
    var retry: (() -> Unit)? = null
    fun beganSending() { flying += 1 }
    fun endedSending() { flying = (flying - 1).coerceAtLeast(0) }
    /** Photos to send: paths on this phone, already scaled to 1568 px JPEG. */
    val attachments = mutableStateListOf<String>()
    /** Documents to send: name -> (text read out of it, pictures of its pages). */
    val docs = mutableStateMapOf<String, Pair<String, List<String>>>()
    /** Where each document was kept on this phone, so the agent can upload the file itself. */
    val docPaths = mutableStateMapOf<String, String>()

    /** The documents as text after the words (what the agent reads). */
    fun docText(): String = docs.entries.joinToString("") { (n, d) ->
        "\n\n[Attached document: $n${docPaths[n]?.let { p -> ", file saved at $p (you can upload it with browser_upload)" }.orEmpty()}]\n${d.first.take(30_000)}"
    }

    /** Every picture that goes with the message: the photos, then up to twelve document pages (a PDF's slices). */
    fun images(): List<String> = attachments.toList() + docs.values.flatMap { it.second }.take(12)

    fun isEmpty() = draft.isBlank() && attachments.isEmpty() && docs.isEmpty()

    fun clear() { attachments.clear(); docs.clear(); docPaths.clear() }

    /** The send of [words] and [pics] was accepted: only now do they leave the box. Words that came since stay. */
    fun accepted(words: String, pics: List<String>) {
        val d = draft
        draft = when {
            d.trim() == words.trim() -> ""
            d.startsWith(words) -> d.substring(words.length).trimStart()
            // Whitespace-normalised compare: a dictation may have tidied the words in between.
            d.trim().replace(Regex("\\s+"), " ") == words.trim().replace(Regex("\\s+"), " ") -> ""
            else -> d
        }
        attachments.removeAll(pics.toSet()); docs.clear(); docPaths.clear()
        failed = null; retry = null
    }
}

/**
 * The message boxes, for the whole app: one per chat ("main", "chat:<id>", "reply:<card>"), outside every screen, their
 * words on disk (data/DraftStore). A reload, a reconnect, a rotation or a dictation still running all see the same box.
 */
object ComposerBoxes {
    private val boxes = HashMap<String, ComposerBox>()
    fun box(key: String): ComposerBox = synchronized(boxes) { boxes.getOrPut(key) { ComposerBox(com.past9.phoneaos.data.DraftStore.cell(key), key) } }
    /** Emptied for good: the words sent, the card gone. */
    fun forget(key: String) { synchronized(boxes) { boxes.remove(key) }; com.past9.phoneaos.data.DraftStore.cell(key).value = "" }
    internal fun dropForTest() = synchronized(boxes) { boxes.clear() }
}

/**
 * The reply boxes under answer cards, kept outside the chat list: a card that scrolls out of view (the keyboard
 * opening moves the list), a trip into the project chat and back, or the photo picker coming back must not throw
 * away what he typed or the photo he picked. One per card; gone once the reply is sent.
 */
object ReplyDrafts {
    private val open = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    private fun key(id: String) = "reply:$id"
    fun box(id: String): ComposerBox = ComposerBoxes.box(key(id))
    /** Open when he opened it, and after a restart when it still holds words. */
    fun isOpen(id: String): Boolean = open[id] ?: com.past9.phoneaos.data.DraftStore.has(key(id))
    fun setOpen(id: String, on: Boolean) { open[id] = on }
    /** After a reply went out: an empty box, closed. */
    fun sent(id: String) { ComposerBoxes.forget(key(id)); open.remove(id) }
}

/** Everything a message box can do besides typing; one set for every chat on the phone (the main chat's). */
data class ComposerActions(
    val onSend: (String) -> Unit,
    /** Swipe up on Send: answer with a voice note. Null: the swipe sends as usual. */
    val onSendVoice: ((String) -> Unit)? = null,
    val onStop: () -> Unit = {},
    val onAttach: () -> Unit = {},
    val onAttachDoc: () -> Unit = {},
    val onCamera: (Rect) -> Unit = {},
    val onMic: () -> Unit = {},
    val onRemoveAttachment: (String) -> Unit = {},
    val onRemoveDoc: (String) -> Unit = {},
    /** The saved dictations, to copy or insert. Null: no such choice. */
    val onRecordings: (() -> Unit)? = null,
)

/**
 * The pickers, the camera and the mic belong to the activity (Android's result launchers live there). A chat hands
 * them its own [ComposerBox] and the result lands in that box. Provided once by MainActivity.
 */
interface ComposerTools {
    fun pickPhotos(box: ComposerBox)
    fun pickDocs(box: ComposerBox)
    /** The in-place camera grows out of [origin]; its shot goes to [send] (path, caption). */
    fun camera(box: ComposerBox, origin: Rect, send: (String, String) -> Unit)
    /** Dictation into [box]'s words; a second tap stops it. */
    fun mic(box: ComposerBox)
    fun listening(box: ComposerBox): Boolean
    /** The saved dictations; Insert goes into [box]. */
    fun recordings(box: ComposerBox)
}

val LocalComposerTools = staticCompositionLocalOf<ComposerTools?> { null }

/** A message that has not gone through in this long is FAILED, with its words still in the box and Retry beside them. */
const val BOX_GIVE_UP_MS = 180_000L

/**
 * The full set of actions for [box], sending with [send] (words with the documents' text, and every picture).
 * [send] returns once the message was accepted and throws when it was not: only then does the box empty, and on a
 * failure everything stays for another try. [scope] outlives the screen (a send goes on after he leaves the chat).
 */
fun composerActionsFor(box: ComposerBox, tools: ComposerTools?, working: Boolean, scope: CoroutineScope, send: suspend (String, List<String>, List<String>) -> Unit,
                       stop: () -> Unit = {}, sendVoice: (suspend (String, List<String>, List<String>) -> Unit)? = null, onFailed: (String) -> Unit = {}): ComposerActions {
    /** The words of the message on their way now, so a second tap on the SAME message doesn't send it twice. */
    var onItsWay: String? = null
    fun go(words: String, via: suspend (String, List<String>, List<String>) -> Unit) {
        // Only the same message twice is ignored: a new message goes while the agent is still answering the last one.
        if (onItsWay != null && onItsWay!!.trim() == words.trim()) return
        val text = words + box.docText(); val pics = box.images()
        // The documents travel as files of their own, so the computer can open the PDF or the Word file itself.
        val docs = box.docPaths.values.toList()
        if (text.isBlank() && pics.isEmpty() && docs.isEmpty()) return
        onItsWay = words
        box.beganSending()
        scope.launch {
            try {
                // Every send ends SENT or FAILED with Retry within this; the uploads get their own, longer, time inside.
                kotlinx.coroutines.withTimeout(BOX_GIVE_UP_MS) { via(text, pics, docs) }
                box.accepted(words, pics)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                box.failed = "That took too long to send. Your words are still here."
                box.retry = { go(words, via) }
                onFailed(box.failed.orEmpty())
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                box.failed = e.message.orEmpty().ifBlank { "That didn't send." }
                box.retry = { go(words, via) }
                onFailed(box.failed.orEmpty())
            } finally { if (onItsWay == words) onItsWay = null; box.endedSending() }
        }
    }
    return ComposerActions(
        onSend = { go(it, send) }, onSendVoice = sendVoice?.let { v -> { w: String -> go(w, v) } }, onStop = stop,
        onAttach = { tools?.pickPhotos(box) }, onAttachDoc = { tools?.pickDocs(box) },
        onCamera = { r -> tools?.camera(box, r) { path, caption -> scope.launch { try { send(caption, listOf(path), box.docPaths.values.toList()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { onFailed(e.message.orEmpty()) } } } },
        onMic = { tools?.mic(box) },
        onRemoveAttachment = { box.attachments.remove(it) }, onRemoveDoc = { box.docs.remove(it); box.docPaths.remove(it) },
        onRecordings = tools?.let { t -> { t.recordings(box) } },
    )
}
