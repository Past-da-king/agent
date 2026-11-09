package com.past9.phoneaos.remote

/**
 * What goes out as HIS message. The words are exactly what he wrote, and nothing when he wrote nothing: photos or
 * files alone are a whole message. The app never writes a caption for him (9 Nov 2026: a photo sent with no words
 * arrived as a question he never asked).
 */
object Outgoing {
    /** His words, trimmed. Empty when he sent only photos or files. Null when there is nothing to send at all. */
    fun words(text: String, images: List<String>, files: List<String>): String? {
        val said = text.trim()
        return if (said.isEmpty() && images.isEmpty() && files.isEmpty()) null else said
    }

    /**
     * What this phone's own model reads for the turn. A model needs some words to go on, so a message without any gets
     * this note. It is never stored as his message and never shown in the chat.
     */
    fun forModel(words: String): String = words.ifEmpty { NO_WORDS }

    /** A name for a side thread started by a message with no words (a label on the thread, not his message). */
    fun label(images: List<String>, files: List<String>): String = when {
        files.isEmpty() -> if (images.size == 1) "Photo" else "Photos"
        else -> if (images.size + files.size == 1) "File" else "Files"
    }

    const val NO_WORDS = "[The user sent this with no words. Look at what is attached and respond to what it shows.]"
}
