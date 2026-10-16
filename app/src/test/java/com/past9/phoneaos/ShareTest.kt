package com.past9.phoneaos

import com.past9.phoneaos.share.*
import com.past9.phoneaos.tools.MediaTranscribeTool
import org.junit.Assert.*
import org.junit.Test

class ShareTest {
    // ---- text and links ----
    @Test fun `a link is found in shared text and trailing punctuation is dropped`() =
        assertEquals(listOf("https://example.com/a?b=1"), ShareLogic.links("Look at this (https://example.com/a?b=1)."))
    @Test fun `two links and a repeat give two links in order`() =
        assertEquals(listOf("https://a.com", "http://b.org/x"), ShareLogic.links("https://a.com and http://b.org/x then https://a.com"))
    @Test fun `plain text has no links`() = assertTrue(ShareLogic.links("remember the milk").isEmpty())
    @Test fun `null text has no links`() = assertTrue(ShareLogic.links(null).isEmpty())
    @Test fun `text beside a link is what is left`() = assertEquals("Great read", ShareLogic.textBesideLinks("Great read https://news.site/story"))
    @Test fun `domain drops www port and path`() {
        assertEquals("example.com", ShareLogic.domain("https://www.example.com/x/y?z=1"))
        assertEquals("localhost", ShareLogic.domain("http://localhost:8080/a"))
    }

    // ---- single vs multiple, mime routing ----
    @Test fun `mime routes to the right kind`() {
        assertEquals(ShareKind.IMAGE, ShareLogic.kindOf("image/jpeg", "a.jpg"))
        assertEquals(ShareKind.VIDEO, ShareLogic.kindOf("video/mp4", "a.mp4"))
        assertEquals(ShareKind.AUDIO, ShareLogic.kindOf("audio/ogg", "PTT-1.opus"))
        assertEquals(ShareKind.AUDIO, ShareLogic.kindOf("audio/mpeg", "a.mp3"))
        assertEquals(ShareKind.AUDIO, ShareLogic.kindOf("audio/amr", "a.amr"))
        assertEquals(ShareKind.PDF, ShareLogic.kindOf("application/pdf", "a.pdf"))
        assertEquals(ShareKind.FILE, ShareLogic.kindOf("application/zip", "a.zip"))
    }
    @Test fun `a vague mime falls back to the file extension`() {
        assertEquals(ShareKind.AUDIO, ShareLogic.kindOf("application/octet-stream", "PTT-20261011-WA0004.opus"))
        assertEquals(ShareKind.AUDIO, ShareLogic.kindOf("*/*", "note.M4A"))
        assertEquals(ShareKind.VIDEO, ShareLogic.kindOf(null, "clip.MOV"))
        assertEquals(ShareKind.IMAGE, ShareLogic.kindOf("*/*", "p.heic"))
        assertEquals(ShareKind.FILE, ShareLogic.kindOf("*/*", "noext"))
    }
    @Test fun `mimeFor fills in a missing type`() {
        assertEquals("audio/ogg", ShareLogic.mimeFor("application/octet-stream", "x.opus"))
        assertEquals("video/mp4", ShareLogic.mimeFor(null, "x.mp4"))
        assertEquals("image/png", ShareLogic.mimeFor("image/png", "weird"))
    }
    @Test fun `primary kind is the files when there are some, else link, else text`() {
        val link = ShareInput(false, "text/plain", "see https://x.com", null, emptyList())
        val text = ShareInput(false, "text/plain", "just words", null, emptyList())
        assertEquals(ShareKind.LINK, ShareLogic.primaryKind(link, emptyList()))
        assertEquals(ShareKind.TEXT, ShareLogic.primaryKind(text, emptyList()))
        assertEquals(ShareKind.VIDEO, ShareLogic.primaryKind(link, listOf(ShareKind.VIDEO)))
    }

    // ---- file names ----
    @Test fun `safe name strips paths and odd characters but keeps the extension`() {
        assertEquals("a_b_c.opus", ShareLogic.safeName("a b?c.opus"))
        assertEquals("voice_note.ogg", ShareLogic.safeName("/storage/x/voice note.ogg"))
        assertEquals("secret.txt", ShareLogic.safeName("..\\..\\secret.txt"))
        assertEquals("file", ShareLogic.safeName(""))
        assertEquals("file", ShareLogic.safeName(null))
        assertEquals("file.mp4", ShareLogic.safeName("???.mp4"))
    }
    @Test fun `safe name caps the length and keeps no slashes`() {
        val n = ShareLogic.safeName("x".repeat(300) + ".m4a")
        assertTrue(n.length <= 70); assertTrue(n.endsWith(".m4a")); assertFalse(n.contains('/'))
    }
    @Test fun `stored name starts with the timestamp and is unique by index`() {
        assertEquals("1700-clip.mp4", ShareLogic.storedName(1700, "clip.mp4"))
        assertEquals("1700-2-clip.mp4", ShareLogic.storedName(1700, "clip.mp4", 2))
    }

    // ---- data rule ----
    @Test fun `on wifi nothing is held`() {
        assertFalse(ShareLogic.holdForWifi(500L * 1024 * 1024, true, false))
        assertFalse(ShareLogic.holdForWifi(20L * 1024 * 1024, false, false))
    }
    @Test fun `on mobile data a video is always held`() = assertTrue(ShareLogic.holdForWifi(1024, true, true))
    @Test fun `on mobile data a file over 15 MB is held but a small one is not`() {
        assertTrue(ShareLogic.holdForWifi(ShareLogic.BIG_BYTES + 1, false, true))
        assertFalse(ShareLogic.holdForWifi(ShareLogic.BIG_BYTES, false, true))
        assertFalse(ShareLogic.holdForWifi(2L * 1024 * 1024, false, true))
    }

    // ---- sizes and durations ----
    @Test fun `size and duration read plainly`() {
        assertEquals("512 B", ShareLogic.formatSize(512)); assertEquals("2 KB", ShareLogic.formatSize(3000)); assertEquals("1.5 MB", ShareLogic.formatSize(1_572_864))
        assertEquals("0:42", ShareLogic.formatDuration(42_000)); assertEquals("1:05", ShareLogic.formatDuration(65_400)); assertEquals("1:02:03", ShareLogic.formatDuration(3_723_000))
    }

    // ---- frame spacing ----
    @Test fun `frames are spread evenly through the middle of equal slices`() {
        assertEquals(listOf(1_000_000L, 3_000_000L, 5_000_000L, 7_000_000L, 9_000_000L), ShareLogic.frameTimesUs(10_000, 5))
    }
    @Test fun `one frame is the middle`() = assertEquals(listOf(5_000_000L), ShareLogic.frameTimesUs(10_000, 1))
    @Test fun `frame count is capped and floored`() {
        assertEquals(ShareLogic.MAX_FRAMES, ShareLogic.frameTimesUs(60_000, 99).size)
        assertEquals(1, ShareLogic.frameTimesUs(60_000, 0).size)
    }
    @Test fun `an unknown length still gives the first frame`() = assertEquals(listOf(0L), ShareLogic.frameTimesUs(0, 5))
    @Test fun `frame times rise and stay inside the video`() {
        val t = ShareLogic.frameTimesUs(37_123, 7)
        assertEquals(t.sorted(), t); assertTrue(t.all { it in 0..37_123_000L })
    }

    // ---- message composition ----
    private fun voice(size: Long = 90_000) = SharedFile("PTT-1.opus", "/data/files/shared/1-PTT-1.opus", "audio/ogg", size, ShareKind.AUDIO, 42_000)
    private fun video(size: Long = 8_000_000) = SharedFile("clip.mp4", "/data/files/shared/2-clip.mp4", "video/mp4", size, ShareKind.VIDEO, 30_000)

    @Test fun `a lone link is the visible line and the agent is told to read it`() {
        val m = ShareLogic.compose(null, "https://example.com/post", "A good post", emptyList(), emptyList(), false)
        assertEquals("https://example.com/post", m.text.substringBefore("\n\n[Shared with you"))
        assertTrue(m.text.contains("Link: https://example.com/post (example.com)"))
        assertTrue(m.text.contains("Page title: A good post"))
        assertTrue(m.text.contains("web_fetch"))
        assertTrue(m.text.contains("no instruction"))
    }
    @Test fun `a note becomes the visible line and the agent is told to follow it`() {
        val m = ShareLogic.compose("  save this for Friday ", "https://e.com", null, emptyList(), emptyList(), false)
        assertEquals("save this for Friday", m.text.substringBefore("\n\n[Shared with you"))
        assertTrue(m.text.contains("Do what they asked")); assertFalse(m.text.contains("no instruction"))
    }
    @Test fun `plain shared text keeps the text`() {
        val m = ShareLogic.compose(null, "Buy milk and eggs", "Shopping", emptyList(), emptyList(), false)
        assertTrue(m.text.startsWith("Buy milk and eggs")); assertTrue(m.text.contains("Subject: Shopping"))
    }
    @Test fun `images attach like a photo and are not listed as files`() {
        val m = ShareLogic.compose(null, null, null, emptyList(), listOf("/data/uploads/a.jpg", "/data/uploads/b.jpg"), false)
        assertEquals(listOf("/data/uploads/a.jpg", "/data/uploads/b.jpg"), m.images)
        assertTrue(m.text.startsWith("Shared 2 photos")); assertTrue(m.text.contains("2 photos are attached")); assertTrue(m.fileNames.isEmpty())
    }
    @Test fun `a voice note gives path type size duration and the tool to use`() {
        val m = ShareLogic.compose(null, null, null, listOf(voice()), emptyList(), false)
        assertTrue(m.text.startsWith("Shared a voice note"))
        assertTrue(m.text.contains("/data/files/shared/1-PTT-1.opus")); assertTrue(m.text.contains("audio/ogg, 87 KB, 0:42 long")); assertTrue(m.text.contains("media_transcribe"))
        assertEquals(listOf("PTT-1.opus"), m.fileNames)
    }
    @Test fun `a video points at both tools`() {
        val m = ShareLogic.compose("what happens here?", null, null, listOf(video()), emptyList(), false)
        assertTrue(m.text.contains("media_frames")); assertTrue(m.text.contains("media_transcribe")); assertFalse(m.text.contains("mobile data"))
    }
    @Test fun `on mobile data a video message says not to upload it`() {
        val m = ShareLogic.compose(null, null, null, listOf(video()), emptyList(), true)
        assertTrue(m.text.contains("mobile data")); assertTrue(m.text.contains("Do not upload"))
    }
    @Test fun `on mobile data a small voice note is not held`() = assertFalse(ShareLogic.compose(null, null, null, listOf(voice()), emptyList(), true).text.contains("Do not upload"))
    @Test fun `the hold notice appears only when something is held`() {
        assertNotNull(ShareLogic.holdNotice(listOf(video()), true)); assertNull(ShareLogic.holdNotice(listOf(video()), false)); assertNull(ShareLogic.holdNotice(listOf(voice()), true))
    }
    @Test fun `nothing in the user facing text has an emoji or an em dash`() {
        val all = listOf(
            ShareLogic.compose(null, "https://x.com", "t", listOf(voice(), video()), listOf("/a.jpg"), true).text,
            ShareLogic.holdNotice(listOf(video()), true).orEmpty(), MediaTranscribeTool.HOLD, MediaTranscribeTool.machineScript("/h/x.ogg"),
        )
        all.forEach { s -> assertFalse(s.contains('—')); assertFalse(s.any { Character.isSurrogate(it) }) }
    }

    // ---- transcription helpers ----
    @Test fun `gemini mime for formats it reads, null for the rest`() {
        assertEquals("audio/ogg", MediaTranscribeTool.geminiMime("PTT.opus")); assertEquals("audio/mp4", MediaTranscribeTool.geminiMime("a.m4a"))
        assertEquals("audio/mp3", MediaTranscribeTool.geminiMime("a.mp3")); assertNull(MediaTranscribeTool.geminiMime("a.amr"))
    }
    @Test fun `the machine script quotes the path and never prints the key`() {
        val s = MediaTranscribeTool.machineScript("/home/u/it's.ogg")
        assertTrue(s.contains("it'\\''s.ogg")); assertFalse(s.contains("echo \"\$key\"")); assertTrue(s.contains("GROQ_API_KEY"))
    }
}
