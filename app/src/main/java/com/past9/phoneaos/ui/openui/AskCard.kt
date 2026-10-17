package com.past9.phoneaos.ui.openui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.past9.phoneaos.agent.AskOptions

/**
 * What an agent asks of the user, drawn as an OpenUI card: who needs you and why, the question, then one full-width
 * button per answer. No is always the last button ([AskOptions.withNo]). Tapping a button answers the question
 * itself; nothing goes into the chat as a message.
 */
object AskProgram {
    /** A string literal in OpenUI Lang. */
    internal fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n") + "\""

    fun build(title: String, subtitle: String?, question: String, options: List<String>): String {
        val all = AskOptions.withNo(options)
        val last = all.lastIndex
        return buildString {
            appendLine("root = Card([head, body, actions])")
            appendLine("head = CardHeader(${q(title)}${subtitle?.takeIf { it.isNotBlank() }?.let { ", ${q(it)}" } ?: ""})")
            appendLine("body = TextContent(${q(question)}, \"large\")")
            appendLine("actions = Buttons([${all.indices.joinToString(", ") { "b$it" }}], \"column\")")
            all.forEachIndexed { i, o -> appendLine("b$i = Button(${q(o)}, Action([@ToAssistant(${q(o)})]), ${q(if (i == 0 && last > 0 && o != AskOptions.NO) "primary" else "secondary")})") }
        }
    }
}

@Composable
fun AskCard(title: String, subtitle: String?, question: String, options: List<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val source = remember(title, subtitle, question, options) { AskProgram.build(title, subtitle, question, options) }
    CompositionLocalProvider(LocalOpenUiPick provides onPick) { OpenUiBlock(source, modifier) }
}
