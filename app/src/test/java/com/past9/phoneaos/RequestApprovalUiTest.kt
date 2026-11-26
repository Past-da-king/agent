package com.past9.phoneaos

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import com.past9.phoneaos.ui.LocalSheetPreview
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.data.ChatItem
import com.past9.phoneaos.ui.screens.AnsweredForYou
import com.past9.phoneaos.ui.screens.Approval
import com.past9.phoneaos.ui.screens.ApprovalCard
import com.past9.phoneaos.ui.theme.AppTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RequestApprovalUiTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()
    private fun card(withRequest: Boolean): ChatItem {
        val meta = JSONObject().put("summary", "Read the requested folder")
        if (withRequest) meta.put("request", JSONObject().put("id", "user-1").put("at", 1795605660000L).put("text", "Pull the shared folder and read the documents. " + "longword".repeat(40))).put("escalatedWhy", "Approve this read because it follows your request.")
        return ChatItem(id = if (withRequest) 1L else 2L, kind = "approval", text = "Read the folder", meta = meta.toString(), createdAt = 1L)
    }
    @Test fun requestIsParsedAndLegacyCardStillWorks() {
        assertTrue(Approval.of(card(true)).request.startsWith("You asked for this at "))
        assertEquals("Approve this read because it follows your request.", Approval.of(card(true)).recommendation)
        assertEquals("", Approval.of(card(false)).request)
    }
    @Test fun requestCardsAtPhoneWidth() {
        RuntimeEnvironment.setQualifiers("w390dp-h844dp-xxhdpi")
        rule.setContent { AppTheme(dark = dark) { Surface { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            ApprovalCard(card(true), { _, _ -> }, "Nova")
            Spacer(Modifier.height(16.dp))
            ApprovalCard(card(false), { _, _ -> }, "Nova")
        } } } }
        rule.waitForIdle()
        val path = System.getProperty("request.ui.proof", "/tmp/request-phone-proof")
        File(path).mkdirs()
        rule.onRoot().captureRoboImage("$path/request-${if (dark) "dark" else "light"}.png")
    }
    @Test fun historyShowsRequestAndUndoFailure() {
        RuntimeEnvironment.setQualifiers("w390dp-h844dp-xxhdpi")
        val original = card(true)
        val meta = JSONObject(original.meta).put("rid", "server-card-1").put("autoAnswer", JSONObject().put("at", 1795605660000L).put("asker", "Project assistant").put("summary", "Read the requested folder").put("decision", "decline").put("reason", "The request needs a narrower scope.").put("undo", "retry").put("undone", 0L))
        val answered = original.copy(meta = meta.toString())
        rule.setContent { CompositionLocalProvider(LocalSheetPreview provides true) { AppTheme(dark = dark) { Surface { AnsweredForYou(listOf(answered), "Nova") } } } }
        rule.onNodeWithText("Answered for you").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("You asked for this at", substring = true).assertIsDisplayed()
        val path = System.getProperty("request.ui.proof", "/tmp/request-phone-proof")
        File(path).mkdirs()
        rule.onRoot().captureRoboImage("$path/history-${if (dark) "dark" else "light"}.png")
        rule.onNodeWithText("Undo", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Not connected to a computer right now.").assertIsDisplayed()
    }

}
