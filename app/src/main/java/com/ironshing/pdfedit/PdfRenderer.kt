package com.ironshing.pdfedit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer as AndroidPdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

data class PdfBookmark(
    val title: String,
    val pageIndex: Long,
    val children: List<PdfBookmark>
)

/**
 * Wrapper over Android's built-in PdfRenderer for page rendering, with PdfBox-Android
 * used to extract the outline (bookmarks). Owns one open document.
 */
class PdfRenderer(private val context: android.content.Context) {
    /**
     * Serializes access to the underlying AndroidPdfRenderer and the file. renderPage()
     * on Dispatchers.IO and replaceText() / burnStrokesToPage() (which close + reopen
     * the file under the hood) can race otherwise — and AndroidPdfRenderer throws if
     * a Page is closed after its Document.
     */
    private val lock = Any()
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: AndroidPdfRenderer? = null
    private var bookmarksCache: List<PdfBookmark> = emptyList()

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    private var sourceFile: File? = null

    fun open(file: File) = synchronized(lock) {
        closeLocked()
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = fd
        renderer = AndroidPdfRenderer(fd)
        sourceFile = file
        // bookmarks loaded lazily via loadBookmarks()
    }

    val pageCount: Int
        get() = synchronized(lock) { renderer?.pageCount ?: 0 }

    /** Parses outline via PdfBox (slow on big PDFs). Call from a background dispatcher. */
    fun loadBookmarks(): List<PdfBookmark> = synchronized(lock) {
        val file = sourceFile ?: return@synchronized emptyList()
        bookmarksCache = readBookmarks(file)
        return@synchronized bookmarksCache
    }

    /**
     * Render [pageIndex] into a bitmap of [width] × the page's natural aspect-ratio height,
     * applying [rotationDegrees] (0/90/180/270) by post-rotating the bitmap.
     */
    fun renderPage(pageIndex: Int, width: Int, rotationDegrees: Int = 0): Bitmap = synchronized(lock) {
        val r = renderer ?: error("no document open")
        val page = r.openPage(pageIndex)
        try {
            val height = (width.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
            val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            raw.eraseColor(Color.WHITE)
            page.render(raw, null, null, AndroidPdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            if (rotationDegrees % 360 == 0) return@synchronized raw
            val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (rotated !== raw) raw.recycle()
            return@synchronized rotated
        } finally {
            page.close()
        }
    }

    fun bookmarks(): List<PdfBookmark> = bookmarksCache

    /** PDF-points size of [pageIndex] (1 point = 1/72 inch — same unit PdfBox uses). */
    fun pagePdfSize(pageIndex: Int): Pair<Int, Int> = synchronized(lock) {
        val r = renderer ?: error("no document open")
        val page = r.openPage(pageIndex)
        try {
            return@synchronized page.width to page.height
        } finally {
            page.close()
        }
    }

    /**
     * Extract text runs on [pageIndex] (one run per line/string as emitted by PdfBox's
     * PDFTextStripper). Returns runs with PDF-native (bottom-up) coordinates so they
     * can be both displayed as overlays *and* written back into the content stream.
     *
     * Heavy — closes the AndroidPdfRenderer and reopens it. Call from a worker thread.
     */
    fun extractTextRuns(pageIndex: Int): List<TextRun> = synchronized(lock) {
        val file = sourceFile ?: error("no document open")
        val wasOpen = renderer != null
        if (wasOpen) closeLocked()
        return@synchronized try {
            PDDocument.load(file).use { doc ->
                val pageObj = doc.getPage(pageIndex)
                val mediaH = pageObj.mediaBox.height
                val cropBox = pageObj.cropBox
                val cropX0 = cropBox.lowerLeftX
                val cropY0 = cropBox.lowerLeftY
                val cropW = cropBox.width
                val cropH = cropBox.height
                val collected = mutableListOf<TextRun>()
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String, textPositions: List<TextPosition>) {
                        val trimmed = text.trim()
                        if (trimmed.isEmpty() || textPositions.isEmpty()) return
                        // PDFTextStripper reports text positions in MediaBox-relative
                        // user space. The rendered bitmap is the CropBox, so translate.
                        val xMinMedia = textPositions.minOf { it.x }
                        val xMaxMedia = textPositions.maxOf { it.x + it.width }
                        val height = textPositions.maxOf { it.height }
                        val baselineTopDownMedia = textPositions.first().y
                        val baselineBottomUpMedia = mediaH - baselineTopDownMedia
                        val xMin = xMinMedia - cropX0
                        val xMax = xMaxMedia - cropX0
                        val baselinePdf = baselineBottomUpMedia - cropY0
                        // Drop runs entirely outside the visible CropBox.
                        if (xMax < 0 || xMin > cropW) return
                        if (baselinePdf < -height || baselinePdf > cropH + height) return
                        val first = textPositions.first()
                        collected += TextRun(
                            pageIndex = pageIndex,
                            text = text,
                            x = xMin,
                            y = baselinePdf,
                            width = xMax - xMin,
                            height = height,
                            baselineY = baselinePdf,
                            fontName = first.font.name ?: "Unknown",
                            fontSize = first.fontSizeInPt
                        )
                    }
                }
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1
                stripper.getText(doc)
                // Dedupe by position: replaceText() leaves the original text in the
                // content stream (just covered by a whiteout) so PDFTextStripper sees
                // BOTH the original and our Helvetica overlay. The overlay is appended
                // later in the stream, so it comes second in `collected`. Walk forward
                // and drop any earlier run whose bbox overlaps the new one by >50%.
                dedupeOverlappingRuns(collected)
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            if (wasOpen) openLocked(file)
        }
    }

    private fun dedupeOverlappingRuns(runs: List<TextRun>): List<TextRun> {
        val out = mutableListOf<TextRun>()
        for (r in runs) {
            out.removeAll { e ->
                val xOverlap = (minOf(e.x + e.width, r.x + r.width) - maxOf(e.x, r.x))
                    .coerceAtLeast(0f)
                val eTop = e.baselineY + e.height
                val rTop = r.baselineY + r.height
                val yOverlap = (minOf(eTop, rTop) - maxOf(e.baselineY, r.baselineY))
                    .coerceAtLeast(0f)
                val overlap = xOverlap * yOverlap
                val area = (e.width * e.height).coerceAtLeast(1f)
                overlap / area > 0.5f
            }
            out.add(r)
        }
        return out
    }

    /**
     * Replace [run]'s text with [newText] by drawing a white rectangle over the
     * original bbox and rendering [newText] in Helvetica at the original baseline
     * and size. Heavy — closes / reopens the renderer.
     *
     * Alpha caveats: white fill (visible on non-white backgrounds), Helvetica
     * regardless of original font, single line.
     */
    /**
     * Thrown when [newText] contains characters Helvetica can't represent. Surfaces
     * to the UI so we can prompt the user to drop a Unicode font (v0.4.1 work).
     */
    class UnsupportedCharsException(val unsupported: List<Char>) : Exception(
        "Helvetica can't encode these characters: ${unsupported.joinToString("") { it.toString() }}"
    )

    fun replaceText(run: TextRun, newText: String): Unit = synchronized(lock) {
        // Refuse the edit if Helvetica can't represent any character.
        val unsupported = newText.filter { !isWinAnsiChar(it) }.toSet().toList()
        if (unsupported.isNotEmpty()) throw UnsupportedCharsException(unsupported)

        val file = sourceFile ?: error("no document open")
        val wasOpen = renderer != null
        if (wasOpen) closeLocked()
        try {
            PDDocument.load(file).use { doc ->
                val page = doc.getPage(run.pageIndex)
                // TextRun coords are CropBox-relative (matches what we display). PdfBox
                // writes in MediaBox coords, so add the CropBox lower-left back.
                val crop = page.cropBox
                val mediaX = run.x + crop.lowerLeftX
                val mediaBaseline = run.baselineY + crop.lowerLeftY
                // Use a full visual line-height rect so ascenders and descenders are
                // both covered (tp.height is just cap-height, leaks text on both ends).
                val whiteoutAscent = run.fontSize * 0.95f
                val whiteoutDescent = run.fontSize * 0.30f
                val pad = 1.5f
                PDPageContentStream(
                    doc,
                    page,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
                ).use { cs ->
                    cs.setNonStrokingColor(1f, 1f, 1f)
                    cs.addRect(
                        mediaX - pad,
                        mediaBaseline - whiteoutDescent - pad,
                        run.width + 2 * pad,
                        whiteoutAscent + whiteoutDescent + 2 * pad
                    )
                    cs.fill()
                    cs.beginText()
                    cs.setNonStrokingColor(0f, 0f, 0f)
                    // Calibrate Helvetica's point size so its cap height matches the
                    // original glyph height. PDFTextStripper's TextPosition.height is
                    // roughly the original font's cap height; Helvetica's cap height
                    // is ~0.72 of its point size. Falls back to fontSize if height
                    // looks broken.
                    val calibrated =
                        if (run.height in 1f..200f) (run.height / 0.72f).coerceIn(4f, 200f)
                        else run.fontSize
                    cs.setFont(PDType1Font.HELVETICA, calibrated)
                    cs.newLineAtOffset(mediaX, mediaBaseline)
                    cs.showText(newText)
                    cs.endText()
                }
                doc.save(file)
            }
        } catch (e: Exception) {
            Log.e("PdfEdit", "replaceText failed (page=${run.pageIndex}, text=\"$newText\")", e)
            throw e
        } finally {
            if (wasOpen) openLocked(file)
        }
    }

    private fun isWinAnsiChar(ch: Char): Boolean = when (ch.code) {
        in 0x20..0x7E,                    // printable ASCII
        in 0xA0..0xFF,                    // Latin-1 supplement
        0x2018, 0x2019,                   // ‘ ’
        0x201C, 0x201D,                   // “ ”
        0x2013, 0x2014,                   // – —
        0x2022,                           // •
        0x2026,                           // …
        0x20AC,                           // €
        0x009                             // tab — we'll render as space
        -> true
        else -> false
    }

    /**
     * Burn [strokesPdf] (each stroke is a list of (x, y) points already in PDF coords —
     * origin bottom-left, points) onto [pageIndex]'s content stream. After writing the
     * file is closed and reopened so renderPage() reflects the new content.
     */
    fun burnStrokesToPage(
        pageIndex: Int,
        strokesPdf: List<List<FloatArray>>,
        rgb: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
        lineWidth: Float = 2f
    ): Unit = synchronized(lock) {
        val file = sourceFile ?: error("no document open")
        val wasOpen = renderer != null
        if (wasOpen) closeLocked()
        try {
            PDDocument.load(file).use { doc ->
                val page = doc.getPage(pageIndex)
                // Strokes arrive in CropBox-relative coords; PdfBox needs MediaBox coords.
                val crop = page.cropBox
                val dx = crop.lowerLeftX
                val dy = crop.lowerLeftY
                PDPageContentStream(
                    doc,
                    page,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
                ).use { cs ->
                    cs.setStrokingColor(rgb.first, rgb.second, rgb.third)
                    cs.setLineWidth(lineWidth)
                    cs.setLineCapStyle(1) // round
                    cs.setLineJoinStyle(1) // round
                    for (stroke in strokesPdf) {
                        if (stroke.size < 1) continue
                        val first = stroke[0]
                        cs.moveTo(first[0] + dx, first[1] + dy)
                        for (i in 1 until stroke.size) {
                            cs.lineTo(stroke[i][0] + dx, stroke[i][1] + dy)
                        }
                        cs.stroke()
                    }
                }
                doc.save(file)
            }
        } finally {
            if (wasOpen) openLocked(file)
        }
    }

    private fun readBookmarks(file: File): List<PdfBookmark> {
        return try {
            PDDocument.load(file).use { doc ->
                val outline = doc.documentCatalog.documentOutline ?: return emptyList()
                outline.toBookmarks(doc)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun PDDocumentOutline.toBookmarks(doc: PDDocument): List<PdfBookmark> {
        val out = mutableListOf<PdfBookmark>()
        var item = firstChild
        while (item != null) {
            out += item.toBookmark(doc)
            item = item.nextSibling
        }
        return out
    }

    private fun PDOutlineItem.toBookmark(doc: PDDocument): PdfBookmark {
        val pageIdx = resolvePageIndex(doc).toLong()
        val children = mutableListOf<PdfBookmark>()
        var c = firstChild
        while (c != null) {
            children += c.toBookmark(doc)
            c = c.nextSibling
        }
        return PdfBookmark(title = title ?: "", pageIndex = pageIdx, children = children)
    }

    private fun PDOutlineItem.resolvePageIndex(doc: PDDocument): Int {
        val dest = destination ?: (action as? PDActionGoTo)?.destination
        val pageDest = dest as? PDPageDestination ?: return 0
        // PDFBox returns the resolved page; pageNumber is 1-based in some APIs
        val pn = try { pageDest.retrievePageNumber() } catch (_: Exception) { -1 }
        return if (pn >= 0) pn else 0
    }

    fun close() = synchronized(lock) {
        closeLocked()
        sourceFile = null
    }

    /** Open under an already-held [lock]. Used by internal close+write+reopen pairs. */
    private fun openLocked(file: File) {
        closeLocked()
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = fd
        renderer = AndroidPdfRenderer(fd)
        sourceFile = file
    }

    /** Tear down the AndroidPdfRenderer + FD but keep [sourceFile] so reopen still knows the path. */
    private fun closeLocked() {
        try {
            renderer?.close()
        } catch (_: Exception) {
        }
        renderer = null
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
        bookmarksCache = emptyList()
    }
}
