package com.past9.phoneaos.docs

/**
 * How a PDF page becomes pictures a model can read (30 Oct 2026, "0_Chapter_1.pdf arrived as 6 images at 1081x1400, so
 * tall pages are squashed and unreadable, and text was read only from pages 1 and 2").
 *
 * The rule: a page's OWN shape is kept (one scale for both sides, never stretched to fit a box), the page is drawn wide
 * enough for its words to be read, and a tall page (a webtoon strip, a long scan) is cut into slices of one screenful
 * each instead of being squeezed down to fit. Pure maths, so it is tested with no phone.
 */
data class PdfSlice(val top: Int, val height: Int)

data class PdfPlan(val width: Int, val height: Int, val scale: Float, val slices: List<PdfSlice>)

object PdfLayout {
    /** Wide enough that small print is read, and where OCR does its best. */
    const val TARGET_WIDTH = 1240
    /**
     * One picture is one screenful: taller than this and the words get too small to read. 1800 keeps a whole A4 or
     * Letter page (1240 x 1754) as one picture, so only really tall pages are cut.
     */
    const val MAX_SLICE_HEIGHT = 1800
    /** A single page never becomes more than this many pictures (a 30 000 px strip would be a hundred). */
    const val MAX_SLICES_PER_PAGE = 8
    /** One rendered page never takes more than this much memory (width x height pixels at 4 bytes). */
    const val MAX_PAGE_HEIGHT = 8000
    /** Pages read from one PDF, and pictures sent with one message (the message box takes twelve). */
    const val MAX_PAGES = 12
    const val MAX_PICTURES = 12

    /**
     * The pictures for one page: [width] and [height] of the whole page as drawn, [scale] from page points to pixels,
     * and the slices, top to bottom, that together are exactly the page.
     */
    fun plan(
        pageWidth: Int, pageHeight: Int,
        targetWidth: Int = TARGET_WIDTH,
        maxSliceHeight: Int = MAX_SLICE_HEIGHT,
        maxSlices: Int = MAX_SLICES_PER_PAGE,
        maxPageHeight: Int = MAX_PAGE_HEIGHT,
    ): PdfPlan {
        if (pageWidth <= 0 || pageHeight <= 0) return PdfPlan(0, 0, 1f, emptyList())
        // The width we want, unless that would make the page taller than we can hold in memory.
        var scale = targetWidth.toFloat() / pageWidth.toFloat()
        val room = maxPageHeight.toFloat() / pageHeight.toFloat()
        if (scale > room) scale = room
        val width = (pageWidth * scale).toInt().coerceAtLeast(1)
        val height = (pageHeight * scale).toInt().coerceAtLeast(1)
        // Even slices, so no picture is a sliver: as many as fit, up to the page's own limit.
        val parts = ((height + maxSliceHeight - 1) / maxSliceHeight).coerceIn(1, maxSlices)
        val each = (height + parts - 1) / parts
        val slices = ArrayList<PdfSlice>(parts)
        var top = 0
        while (top < height) {
            val h = minOf(each, height - top)
            slices += PdfSlice(top, h)
            top += h
        }
        return PdfPlan(width, height, scale, slices)
    }

    /**
     * What he is told about a PDF, in the message: how many pages the file has, how many were read, and how many
     * pictures came from them. Never a silent six of thirty-four.
     */
    fun summary(pagesInFile: Int, pagesRead: Int, pictures: Int): String {
        if (pagesInFile <= 0) return "PDF: no pages could be read."
        val head = if (pagesRead >= pagesInFile) "PDF: $pagesInFile page${if (pagesInFile == 1) "" else "s"}, "
        else "PDF: $pagesInFile pages, the first $pagesRead read, "
        val tail = when {
            pictures == 0 -> "no readable page pictures came through; the file itself is saved on the phone."
            pagesRead == pictures -> "$pictures picture${if (pictures == 1) "" else "s"}."
            else -> "$pictures picture${if (pictures == 1) "" else "s"} (tall pages cut into slices so the words stay readable)."
        }
        return head + tail
    }
}
