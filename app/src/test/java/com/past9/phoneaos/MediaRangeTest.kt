package com.past9.phoneaos

import com.past9.phoneaos.remote.MediaFiles
import com.past9.phoneaos.remote.MediaRange
import com.past9.phoneaos.remote.ShownFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pieces under the player: a Range header into an answer the phone's player can seek in, and the chunk cache that
 * gets a shown file's bytes from the computer one piece at a time (0.15.27, media.fetch).
 */
class MediaRangeTest {

    private fun shown(token: String, size: Long, name: String = "clip.mp4") =
        ShownFile(chat = "main", token = token, name = name, mime = "video/mp4", kind = "video", size = size)

    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "media-files-test-" + System.nanoTime())

    @Test
    fun noRangeHeaderIsTheWholeFile() {
        assertEquals(MediaRange.All, MediaRange.of(null, 4096))
        assertEquals(MediaRange.All, MediaRange.of("", 4096))
    }

    @Test
    fun aRangeIsExactlyThoseBytes() {
        assertEquals(MediaRange.Part(1000, 1099), MediaRange.of("bytes=1000-1099", 4096))
        assertEquals(MediaRange.Part(4000, 4095), MediaRange.of("bytes=4000-", 4096))
        assertEquals(MediaRange.Part(0, 4095), MediaRange.of("bytes=0-999999999", 4096))
        assertEquals(MediaRange.Part(3996, 4095), MediaRange.of("bytes=-100", 4096))
    }

    @Test
    fun aRangeThatIsNotInTheFileIsRefused() {
        assertEquals(MediaRange.Bad, MediaRange.of("bytes=4096-4200", 4096))
        assertEquals(MediaRange.Bad, MediaRange.of("bytes=200-100", 4096))
        assertEquals(MediaRange.Bad, MediaRange.of("bytes=abc", 4096))
        assertEquals(MediaRange.Bad, MediaRange.of("items=0-10", 4096))
        assertEquals(MediaRange.Bad, MediaRange.of("bytes=-0", 4096))
        assertEquals(MediaRange.Bad, MediaRange.of(null, 0))
    }

    @Test
    fun aFileIsReadInChunksOfHalfAMegabyte() {
        assertEquals(0, MediaFiles.chunksFor(0))
        assertEquals(1, MediaFiles.chunksFor(1))
        assertEquals(1, MediaFiles.chunksFor(MediaFiles.CHUNK))
        assertEquals(2, MediaFiles.chunksFor(MediaFiles.CHUNK + 1))
    }

    @Test
    fun aRangeIsGotFromTheComputerOnceAndThenFromTheCache() = runBlocking {
        val dir = tempDir()
        val files = MediaFiles(dir, chunk = 16)
        val held = files.note(shown("a".repeat(32), 100))
        var asks = 0
        val source = ByteArray(100) { (it % 251).toByte() }
        val ask: suspend (Long, Int) -> ByteArray? = { offset, length ->
            asks++
            source.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
        val first = files.bytes(held, 40, 20, ask)
        assertArrayEquals(source.copyOfRange(40, 60), first)
        assertEquals("40 to 59 touches two chunks", 2, asks)
        assertArrayEquals(source.copyOfRange(40, 60), files.bytes(held, 40, 20, ask))
        assertEquals("a range already here costs nothing", 2, asks)
        assertTrue(files.whole(held, ask))
        assertArrayEquals(source, held.path.readBytes())
        assertTrue(held.whole)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun aComputerThatDoesNotAnswerGivesNoBytes() = runBlocking {
        val dir = tempDir()
        val files = MediaFiles(dir, chunk = 16)
        val held = files.note(shown("b".repeat(32), 64))
        val nothing: suspend (Long, Int) -> ByteArray? = { _, _ -> null }
        assertNull(files.bytes(held, 0, 16, nothing))
        var asks = 0
        val short: suspend (Long, Int) -> ByteArray? = { _, _ -> asks++; ByteArray(3) }
        assertNull(files.bytes(held, 0, 16, short))
        assertEquals(1, asks)
        val good: suspend (Long, Int) -> ByteArray? = { offset, length -> ByteArray(length) { (offset + it).toByte() } }
        assertArrayEquals(ByteArray(16) { it.toByte() }, files.bytes(held, 0, 16, good))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun aRangeOutsideTheFileIsNeverAskedFor() = runBlocking {
        val dir = tempDir()
        val files = MediaFiles(dir, chunk = 16)
        val held = files.note(shown("c".repeat(32), 32))
        var asked = false
        val ask: suspend (Long, Int) -> ByteArray? = { _, length -> asked = true; ByteArray(length) }
        assertNull(files.bytes(held, 30, 10, ask))
        assertNull(files.bytes(held, -1, 4, ask))
        assertTrue("nothing should cross the link for a range that is not in the file", !asked)
        dir.deleteRecursively()
        Unit
    }
}
