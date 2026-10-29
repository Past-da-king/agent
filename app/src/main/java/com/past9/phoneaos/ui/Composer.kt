package com.past9.phoneaos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

/**
 * What one message box holds before it is sent: the words, the photos and the documents. The main chat has one, and
 * every project or helper chat opened on the phone has its own, so a photo picked in one never lands in another
 * (29 Oct 2026: the project chat had none, and its sends carried no pictures at all).
 */
class ComposerBox(draftState: MutableState<String>) {
    var draft by draftState
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
}

@Composable
fun rememberComposerBox(): ComposerBox {
    val draft = rememberSaveable { mutableStateOf("") }
    return remember { ComposerBox(draft) }
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
}

val LocalComposerTools = staticCompositionLocalOf<ComposerTools?> { null }

/** The full set of actions for [box], sending with [send] (words with the documents' text, and every picture). */
fun composerActionsFor(box: ComposerBox, tools: ComposerTools?, working: Boolean, send: (String, List<String>) -> Unit, stop: () -> Unit = {}): ComposerActions {
    fun go(words: String) {
        val text = words + box.docText(); val pics = box.images()
        if (text.isBlank() && pics.isEmpty()) return
        send(text, pics); box.clear()
    }
    return ComposerActions(
        onSend = ::go, onSendVoice = null, onStop = stop,
        onAttach = { tools?.pickPhotos(box) }, onAttachDoc = { tools?.pickDocs(box) },
        onCamera = { r -> tools?.camera(box, r) { path, caption -> send(caption, listOf(path)) } },
        onMic = { tools?.mic(box) },
        onRemoveAttachment = { box.attachments.remove(it) }, onRemoveDoc = { box.docs.remove(it); box.docPaths.remove(it) },
    )
}
