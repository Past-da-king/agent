package com.past9.phoneaos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's regex engine (ICU) rejects a bare closing brace that the JVM accepts, so a pattern like `\{\{x}}` passes
 * every test on a PC and then crashes the app the first time it runs on a phone (it did: opening any card, 0.11.14).
 * This reads every Regex("...") in the app source and fails on a `}` that is neither escaped nor closing a `{n,m}` quantifier.
 */
class RegexAndroidSafeTest {
    private val literal = Regex("""Regex\(\"\"\"(.*?)\"\"\"|Regex\(\"((?:[^\"\\]|\\.)*)\"""")

    @Test fun noBareClosingBraceInRegexLiterals() {
        val bad = mutableListOf<String>()
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                literal.findAll(line).forEach { m ->
                    val raw = m.groups[1]?.value
                    val pattern = raw ?: m.groups[2]!!.value.replace("\\\\", "\u0000")   // a Kotlin "\\" is one backslash in the pattern
                    // \p{L}, \P{N}, \x{1F000}: a brace that belongs to a valid escape.
                    val cleaned = pattern.replace(Regex("""[\\\u0000][pPxX]\{[^}]*\}"""), "").replace(Regex("""\\\}"""), "").replace(Regex("""\u0000\}"""), "")
                        .replace(Regex("""\{\d+(,\d*)?\}"""), "")
                    if ('}' in cleaned) bad += "${f.path}:${i + 1}: $pattern"
                }
            }
        }
        assertTrue("Regex with an unescaped } (crashes on Android):\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
