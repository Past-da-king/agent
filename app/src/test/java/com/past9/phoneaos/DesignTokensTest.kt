package com.past9.phoneaos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The build fails when UI leaves the app's design language (29 Oct 2026, Ayanda: "off-brand UI everywhere"; every
 * centred pop-up becomes an on-brand slide-up sheet). It reads every source file under src/main and refuses:
 *  - a centred AlertDialog or Dialog (use AgentSheet / ChoiceSheet / ConfirmSheet in ui/Sheets.kt);
 *  - a ModalBottomSheet anywhere but ui/Sheets.kt (every sheet goes through AgentSheet);
 *  - Android's Toast (use AppNotice), a stock OutlinedTextField or TextField (use AppTextField);
 *  - a colour literal (Color(0x...), Color.White/Black/Red...) outside ui/theme/ (name it in Theme.kt);
 *  - a font size outside ui/theme/ (use the type scale);
 *  - a corner radius that is not a token step (8, 12, 20, 28, 36 dp; a pill is RoundedCornerShape(50)).
 * A line may opt out only with "// design: <why>" (e.g. the full-screen photo viewer is not a pop-up).
 */
class DesignTokensTest {
    private val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    private data class Rule(val name: String, val pattern: Regex, val allowedIn: (String) -> Boolean = { false })

    private val rules = listOf(
        Rule("centred AlertDialog (use ConfirmSheet or ChoiceSheet)", Regex("""\b(?:Basic)?AlertDialog\s*\(""")),
        Rule("centred Dialog (use AgentSheet)", Regex("""(?<!\w)Dialog\s*\(""")),
        Rule("stock ModalBottomSheet (use AgentSheet)", Regex("""\bModalBottomSheet\s*\("""), { it == "com/past9/phoneaos/ui/Sheets.kt" }),
        Rule("Android Toast (use AppNotice)", Regex("""\bToast\.makeText\b""")),
        Rule("stock text field (use AppTextField)", Regex("""(?<!\w)(?:OutlinedTextField|TextField)\s*\("""), { it == "com/past9/phoneaos/ui/Sheets.kt" }),
        Rule("colour literal (name it in ui/theme/Theme.kt)", Regex("""\bColor\(0x[0-9A-Fa-f]{6,8}\)"""), { it.startsWith("com/past9/phoneaos/ui/theme/") }),
        Rule("named stock colour (use the theme)", Regex("""\bColor\.(White|Black|Red|Green|Blue|Yellow|Gray|LightGray|DarkGray|Magenta|Cyan)\b"""),
            { it.startsWith("com/past9/phoneaos/ui/theme/") || it == "com/past9/phoneaos/ui/BrainIcon.kt" }),
        Rule("font size outside the type scale", Regex("""\bfontSize\s*=\s*\d"""), { it.startsWith("com/past9/phoneaos/ui/theme/") }),
    )

    /** The corner steps (tokens.css --r-sm/md/lg/xl and the phone's 8). */
    private val radiusSteps = setOf(8, 12, 20, 28, 36)
    private val radiusCall = Regex("""RoundedCornerShape\(([^()]*)\)""")
    private val dpLiteral = Regex("""(?<![\w.])(\d+)\.dp""")

    private fun files() = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun rel(f: File) = f.relativeTo(root).invariantSeparatorsPath

    fun findings(): List<String> {
        val out = ArrayList<String>()
        for (f in files()) {
            val path = rel(f)
            f.readLines().forEachIndexed { i, line ->
                if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*") || line.contains("// design:")) return@forEachIndexed
                for (r in rules) if (!r.allowedIn(path) && r.pattern.containsMatchIn(line)) out += "$path:${i + 1}: ${r.name}: ${line.trim().take(120)}"
                if (!path.startsWith("com/past9/phoneaos/ui/theme/")) radiusCall.findAll(line).forEach { m ->
                    dpLiteral.findAll(m.groupValues[1]).map { it.groupValues[1].toInt() }.filter { it !in radiusSteps }.forEach { n ->
                        out += "$path:${i + 1}: radius $n dp is not a token step (8, 12, 20, 28, 36 or a pill): ${m.value}"
                    }
                }
            }
        }
        return out
    }

    @Test
    fun everyScreenUsesTheAppsOwnSheetsColoursTypeAndCorners() {
        val found = findings()
        assertTrue("Off-brand UI (${found.size}):\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun theCheckCatchesWhatItIsFor() {
        // The check itself goes red on each kind it is for (a green check proves nothing until it has gone red).
        val bad = listOf(
            "androidx.compose.material3.AlertDialog(onDismissRequest = {}, confirmButton = {})",
            "Dialog(onDismissRequest = {}) {}",
            "ModalBottomSheet(onDismissRequest = {}) {}",
            "android.widget.Toast.makeText(c, t, 1).show()",
            "OutlinedTextField(v, { })",
            "Box(Modifier.background(Color(0xFF123456)))",
            "Text(x, color = Color.White)",
            "Text(x, fontSize = 13.sp)",
        )
        bad.forEach { line -> assertTrue("not caught: $line", rules.any { it.pattern.containsMatchIn(line) }) }
        val radius = "Surface(shape = RoundedCornerShape(24.dp))"
        assertTrue(radiusCall.findAll(radius).any { m -> dpLiteral.findAll(m.groupValues[1]).any { it.groupValues[1].toInt() !in radiusSteps } })
        // And lets the app's own through.
        val good = listOf("com.past9.phoneaos.ui.AgentSheet(onDismiss) {}", "com.past9.phoneaos.ui.AppTextField(v, { })", "AppNotice.say(t)", "RoundedCornerShape(50)", "RoundedCornerShape(Radius.lg)")
        good.forEach { line -> assertTrue("wrongly caught: $line", rules.none { it.pattern.containsMatchIn(line) } && radiusCall.findAll(line).none { m -> dpLiteral.containsMatchIn(m.groupValues[1]) }) }
    }
}
