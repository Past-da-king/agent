package com.past9.phoneaos.remote

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * One message on its way to whichever brain will take it (protocol s8, chat.sendOnce).
 *
 * An open socket says nothing about the computer behind it: a frozen computer keeps the connection and answers nobody,
 * and the socket only falls over after a whole ping cycle, 40 s. So a send does not wait for that: no word back within
 * [ACK_WAIT_MS] and this brain is left and the next one is tried, with the same id, so no brain can run it twice
 * (30 Oct 2026, he sent a message while the computer was frozen for 54 s and it went nowhere).
 *
 * A send is ACCEPTED when a brain has the message, not when its answer is ready. A computer older than 0.2.116 has no
 * chat.sendOnce and answers the old chat.send only when the whole turn has ended, which can be minutes: waiting for that
 * is what left his words in the message box and the Send button spinning for the length of a turn (30 Oct 2026, "text
 * stays in the input box after sending", "the send icon shows loading while the agent works"). So the old call is
 * HANDED OVER: the frame leaves the phone and the send is done at once; the turn's answer arrives on its own.
 */
internal class SendFlow(
    /** Is a socket open now, waiting up to this long for one? */
    private val linkUp: suspend (Long) -> Boolean,
    /** Whatever must be on this brain before the message (its pictures and files). */
    private val prepare: suspend () -> Unit,
    /** One chat.sendOnce on the socket that is open now. */
    private val attempt: suspend (fresh: Boolean) -> JSONObject?,
    private val socketOpen: () -> Boolean,
    /** This brain isn't answering: go to the next one. */
    private val leave: () -> Unit,
    /**
     * A brain too old for chat.sendOnce: put the old chat.send on the wire, once, and do not wait for its answer.
     * True: the frame went out (the brain has it). False: there is no socket to put it on.
     */
    private val legacy: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val ackWaitMs: Long = ACK_WAIT_MS,
    private val prepareWaitMs: Long = PREPARE_WAIT_MS,
    private val linkWaitMs: Long = LINK_WAIT_MS,
    private val giveUpMs: Long = GIVE_UP_MS,
    private val pauseMs: Long = 1_000L,
) {
    suspend fun run() {
        val deadline = now() + giveUpMs
        var fresh = false
        while (true) {
            if (now() >= deadline) throw RpcException(NO_BRAIN)
            if (!linkUp(linkWaitMs)) {
                leave()
                delay(pauseMs)
                continue
            }
            val answer = try {
                withTimeout(prepareWaitMs) { prepare() }
                withTimeout(ackWaitMs) { attempt(fresh) }
            } catch (e: TimeoutCancellationException) {
                // The pictures are still going up: that is not the brain being frozen, it is this phone's upload.
                leave()
                continue
            } catch (e: RpcException) {
                if (e.message.orEmpty().contains("not available", ignoreCase = true)) {
                    if (legacy()) return
                    // No socket to hand it to: the link went as we tried, so the next brain gets it.
                    if (socketOpen()) throw RpcException(NOT_HANDED_OVER)
                    continue
                }
                // The computer said no: that is an answer. The link going is not, so the next brain gets it.
                if (socketOpen()) throw e
                continue
            }
            when {
                answer?.optBoolean("accepted") == true -> return
                answer?.optBoolean("stale") == true -> fresh = true
                else -> throw RpcException("Your computer didn't take that message.")
            }
        }
    }

    companion object {
        /** A brain that is fine answers in well under a second, over a relay too. */
        const val ACK_WAIT_MS = 6_000L
        /** Pictures and files go up before the message: several on mobile data can take a while. */
        const val PREPARE_WAIT_MS = 90_000L
        const val LINK_WAIT_MS = 8_000L
        /** Every brain may be away for a moment; his words stay in the box after this. */
        const val GIVE_UP_MS = 120_000L
        /** A brain that didn't answer a message isn't tried again for this long. */
        const val SKIP_MS = 30_000L
        const val NO_BRAIN = "None of your computers answered for 2 minutes"
        const val NOT_HANDED_OVER = "The link to your computer dropped before that message went."
    }
}
