package com.past9.phoneaos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.past9.phoneaos.agent.AgentStatus
import com.past9.phoneaos.people.GiftIdea
import com.past9.phoneaos.people.ImportantDate
import com.past9.phoneaos.people.Person
import com.past9.phoneaos.people.PersonLog
import com.past9.phoneaos.ui.screens.AddGiftSheet
import com.past9.phoneaos.ui.screens.AddPersonSheet
import com.past9.phoneaos.ui.screens.HomeActions
import com.past9.phoneaos.ui.screens.HomeScreen
import com.past9.phoneaos.ui.screens.HomeState
import com.past9.phoneaos.ui.screens.AddDateSheet
import com.past9.phoneaos.ui.screens.LogSheet
import com.past9.phoneaos.ui.screens.PeopleActions
import com.past9.phoneaos.ui.screens.PeopleScreen
import com.past9.phoneaos.ui.screens.PersonActions
import com.past9.phoneaos.ui.screens.PersonScreen
import com.past9.phoneaos.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** The People screen and one person's page at phone size (390dp), light and dark. PNGs land in app/build/people/. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class PeopleShotsTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(dark = dark) {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        rule.mainClock.advanceTimeBy(900)
        rule.onRoot().captureRoboImage(File("build/people/$name-${if (dark) "dark" else "light"}.png").path)
    }

    private val now = System.currentTimeMillis()
    private val day = 86_400_000L

    private val thandi = Person(
        id = 1, name = "Thandi Nkosi", note = "Cousin. Lives in Pinetown, moving to Cape Town in January.",
        dates = listOf(ImportantDate("d1", "Birthday", LocalDate.now().plusDays(4), yearly = true, daysBefore = 3),
            ImportantDate("d2", "Anniversary", LocalDate.now().plusDays(120), yearly = true, daysBefore = 7)),
        gifts = listOf(GiftIdea("g1", "A book on growing herbs", "Birthday"), GiftIdea("g2", "Voucher for the garden centre", "")),
        lastLogAt = now - 5 * day, createdAt = now, updatedAt = now,
    )
    private val sipho = Person(id = 2, name = "Sipho Mahlangu", note = "Old friend from varsity. Coffee when he is in town.",
        dates = listOf(ImportantDate("d1", "Birthday", LocalDate.now().plusDays(40), yearly = true, daysBefore = 3)),
        gifts = listOf(GiftIdea("g1", "A tin of good coffee", "Birthday")), lastLogAt = now - 64 * day, createdAt = now, updatedAt = now)
    private val mana = Person(id = 3, name = "Mana", note = "Mom, Hammarsdale. Calls on Sunday.",
        lastLogAt = now - 2 * day, createdAt = now, updatedAt = now)
    private val lerato = Person(id = 4, name = "Lerato Mokoena", note = "Works with Ben. Runs the creek project.",
        lastLogAt = now - 21 * day, createdAt = now, updatedAt = now)

    private val logs = listOf(
        PersonLog(11, 1, "call", "Talked about the move to Cape Town in January; she needs a removal company.", now - 5 * day),
        PersonLog(10, 1, "message", "Sent her the photos from the wedding.", now - 26 * day),
        PersonLog(9, 1, "note", "Her birthday is coming up. She mentioned the herb gardening book.", now - 40 * day),
    )

    @Test fun peopleList() {
        shot("people-list") { PeopleScreen(listOf(thandi, sipho, mana, lerato), PeopleActions(), now = now) }
    }

    @Test fun peopleEmpty() {
        shot("people-empty") { PeopleScreen(emptyList(), PeopleActions(), now = now) }
    }

    @Test fun personPage() {
        shot("person-page") { PersonScreen(thandi, logs, PersonActions(), now = now) }
    }

    @Test fun personNoEntries() {
        shot("person-no-entries") { PersonScreen(Person(id = 9, name = "Nomsa", createdAt = now, updatedAt = now), emptyList(), PersonActions(), now = now) }
    }

    @Test fun homeRowThatLeadsToPeople() {
        val state = HomeState("Your agent", "Sam", AgentStatus(), emptyList(), emptyList(), emptyList(), emptyList(), now = now,
            people = listOf(thandi, sipho, mana))
        shot("home-people-row") { HomeScreen(state, HomeActions()) }
    }

    @Test fun addPersonSheet() {
        shot("sheet-add-person") { AddPersonSheet(onDismiss = {}, onImport = {}, onAdd = { _, _ -> }) }
    }

    @Test fun logSheet() {
        shot("sheet-log-call") { LogSheet("call", "Thandi Nkosi", onDismiss = {}, onLog = {}) }
    }

    @Test fun addDateSheet() {
        shot("sheet-add-date") { AddDateSheet("Thandi Nkosi", onDismiss = {}, onAdd = { _, _, _, _ -> }) }
    }

    @Test fun addGiftSheet() {
        shot("sheet-add-gift") { AddGiftSheet("Thandi Nkosi", onDismiss = {}, onAdd = { _, _ -> }) }
    }
}
