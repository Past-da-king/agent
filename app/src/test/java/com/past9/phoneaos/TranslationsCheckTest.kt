package com.past9.phoneaos

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs scripts/check-translations.py and scripts/test_check_translations.py. The first holds every values-* folder
 * to the English one (same keys, placeholders, HTML, escaping, plural categories, no English left); the second
 * proves the checker goes red on planted bad files. Skipped only when python3 is not installed.
 */
class TranslationsCheckTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "scripts/check-translations.py").isFile }
    private fun python3(vararg args: String): Pair<Int, String>? = try {
        val p = ProcessBuilder(listOf("python3", "-I") + args).directory(root).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor() to out
    } catch (e: java.io.IOException) { null }

    @Test fun everyTranslationMatchesTheEnglishStrings() {
        val r = python3("scripts/check-translations.py", "--quiet"); assumeTrue("python3 not installed", r != null)
        assertEquals(r!!.second, 0, r.first)
    }

    @Test fun theCheckerGoesRedOnBadFiles() {
        val r = python3("scripts/test_check_translations.py"); assumeTrue("python3 not installed", r != null)
        assertEquals(r!!.second, 0, r.first)
    }
}
