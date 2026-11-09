package com.past9.phoneaos.remote

/**
 * What came of asking the computer for a new chat (memory.newChat), which is the first half of clearing a chat on this
 * phone (issue #14, 4 Nov 2026). The phone clears itself whatever this says; the outcome only decides what the person
 * is told, because a computer too old to know the call and a computer that cannot be reached need different things done.
 */
enum class NewChatOutcome {
    /** The computer wrote its New chat divider and forgot the thread's session: its next turn carries none of the old chat. */
    STARTED,
    /** This phone is not using a computer, so its own chat is the only one there is and it is now clear. */
    NOT_LINKED,
    /** The computer's app does not know memory.newChat: its chat stays as it is until that app is updated. */
    COMPUTER_OLDER,
    /** The computer did not answer: its chat is untouched and will show again when it syncs. */
    UNREACHABLE;

    companion object {
        /**
         * Which failure this is. The desktop app answers a call it does not know with NOT_AVAILABLE, the words "not
         * available on this device" (agent-desktop-app apps/desktop/src/shared/rpc-methods.ts), and the phone already
         * reads exactly those words for the same reason elsewhere; anything else is the computer not answering now.
         */
        fun of(error: Throwable): NewChatOutcome =
            if (error.message.orEmpty().contains("not available")) COMPUTER_OLDER else UNREACHABLE
    }
}
