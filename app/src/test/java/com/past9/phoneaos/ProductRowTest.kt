package com.past9.phoneaos

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.past9.phoneaos.ui.MdPart
import com.past9.phoneaos.ui.MdTable
import com.past9.phoneaos.ui.openui.LocalOpenUiSend
import com.past9.phoneaos.ui.openui.OpenUiBlock
import com.past9.phoneaos.ui.openui.OpenUiLang
import com.past9.phoneaos.ui.openui.OpenUiPrompt
import com.past9.phoneaos.ui.theme.AppTheme
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A row of Products: one card per item to swipe through, each with its price, stars and an Add to cart that names that product. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2000dp-port-night-xxhdpi")
class ProductRowTest {
    @get:Rule val rule = createComposeRule()
    private val source = MdTable.split(javaClass.classLoader!!.getResource("openui/products.md")!!.readText()).filterIsInstance<MdPart.OpenUi>().first().source
    private val sent = mutableListOf<String>()

    @Test fun theRowParsesIntoFourProducts() {
        val r = OpenUiLang.render(OpenUiLang.parse(source))
        assertTrue(r.errors.toString(), r.errors.isEmpty()); assertTrue(r.unresolved.isEmpty())
        val kids = (r.root!!.props["children"] as List<*>).filterIsInstance<OpenUiLang.Node>()
        assertEquals(List(4) { "Product" }, kids.map { it.type })
        assertEquals(4.6, kids[0].props["rating"] as Double, 0.0)
        assertNull("no rating is not an invented rating", kids[3].props["rating"])
    }

    @Test fun addToCartNamesTheProductItBelongsTo() {
        rule.setContent { AppTheme(dark = true) { Surface(color = MaterialTheme.colorScheme.surface) {
            CompositionLocalProvider(LocalOpenUiSend provides { sent += it }) { OpenUiBlock(source) } } } }
        rule.onAllNodesWithText("Add to cart")[0].performClick()
        val msg = sent.single()
        assertTrue(msg, msg.startsWith("Add to cart") && msg.contains("Orbit M3") && msg.contains("R899") && msg.contains("DM-1001"))
        assertFalse(msg, msg.contains("Glide"))
    }

    @Test fun priceAndOldPriceBothShow() {
        rule.setContent { AppTheme(dark = true) { Surface(color = MaterialTheme.colorScheme.surface) { OpenUiBlock(source) } } }
        rule.onAllNodesWithText("R899")[0].assertExists(); rule.onAllNodesWithText("R1,099")[0].assertExists()
        rule.onAllNodesWithText("Best value")[0].assertExists()
    }

    @Test fun theAgentIsTaughtToUseProductsForShopping() {
        val prompt = OpenUiPrompt.section(ApplicationProvider.getApplicationContext())
        assertTrue(prompt.contains("Product(image?: string, name: string, price?: string, rating?: number"))
        assertTrue(prompt.contains("one Product per item") && prompt.contains("never invent numbers"))
    }
}
