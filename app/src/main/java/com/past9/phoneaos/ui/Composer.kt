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
    /** A send is on its way: the words stay in the box until the computer took them. */
    var sending by mutableStateOf(false)
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

    /** Every picture that goes with the message: the photos, then up to six document pages. */
    fun images(): List<String> = attachments.toList() + docs.values.flatMap { it.second }.take(6)

    fun isEmpty() = draft.isBlank() && attachments.isEmpty() && docs.isEmpty()

    fun clear() { attachments.clear(); docs.clear(); docPaths.clear() }

    /** The send of [words] and [pics] was accepted: only now do they leave the box. Words that came since stay. */
    fun accepted(words: String, pics: List<String>) {
        val d = draft
        draft = when {
            d.trim() == words.trim() -> ""
            d.startsWith(words) -> d.substring(words.length).trimStart()
            else -> d
        }
        attachments.removeAll(pics.toSet()); docs.clear(); docPaths.clear()
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

/**
 * The full set of actions for [box], sending with [send] (words with the documents' text, and every picture).
 * [send] returns once the message was accepted and throws when it was not: only then does the box empty, and on a
 * failure everything stays for another try. [scope] outlives the screen (a send goes on after he leaves the chat).
 */
fun composerActionsFor(box: ComposerBox, tools: ComposerTools?, working: Boolean, scope: CoroutineScope, send: suspend (String, List<String>) -> Unit,
                       stop: () -> Unit = {}, sendVoice: (suspend (String, List<String>) -> Unit)? = null, onFailed: (String) -> Unit = {}): ComposerActions {
    fun go(words: String, via: suspend (String, List<String>) -> Unit) {
        if (box.sending) return
        val text = words + box.docText(); val pics = box.images()
        if (text.isBlank() && pics.isEmpty()) return
        box.sending = true
        scope.launch {
            try { via(text, pics); box.accepted(words, pics) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { onFailed(e.message.orEmpty()) }
            finally { box.sending = false }
        }
    }
    return ComposerActions(
        onSend = { go(it, send) }, onSendVoice = sendVoice?.let { v -> { w: String -> go(w, v) } }, onStop = stop,
        onAttach = { tools?.pickPhotos(box) }, onAttachDoc = { tools?.pickDocs(box) },
        onCamera = { r -> tools?.camera(box, r) { path, caption -> scope.launch { try { send(caption, listOf(path)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { onFailed(e.message.orEmpty()) } } } },
        onMic = { tools?.mic(box) },
        onRemoveAttachment = { box.attachments.remove(it) }, onRemoveDoc = { box.docs.remove(it); box.docPaths.remove(it) },
        onRecordings = tools?.let { t -> { t.recordings(box) } },
    )
}
