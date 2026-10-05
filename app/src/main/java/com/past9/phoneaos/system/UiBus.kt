package com.past9.phoneaos.system

import kotlinx.coroutines.flow.MutableSharedFlow

/** Things the agent asks the open screen to do: e.g. show a sign-in page in the built-in browser. */
object UiBus {
    data class OpenInBrowser(val url: String, val profile: String? = null)
    val openInBrowser = MutableSharedFlow<OpenInBrowser>(replay = 1, extraBufferCapacity = 4)
}
