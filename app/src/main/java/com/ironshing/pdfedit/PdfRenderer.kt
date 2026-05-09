package com.ironshing.pdfedit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer as AndroidPdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
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
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: AndroidPdfRenderer? = null
    private var bookmarksCache: List<PdfBookmark> = emptyList()

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    private var sourceFile: File? = null

    fun open(file: File) {
        close()
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = fd
        renderer = AndroidPdfRenderer(fd)
        sourceFile = file
        // bookmarks loaded lazily via loadBookmarks()
    }

    val pageCount: Int
        get() = renderer?.pageCount ?: 0

    /** Parses outline via PdfBox (slow on big PDFs). Call from a background dispatcher. */
    fun loadBookmarks(): List<PdfBookmark> {
        val file = sourceFile ?: return emptyList()
        bookmarksCache = readBookmarks(file)
        return bookmarksCache
    }

    /**
     * Render [pageIndex] into a bitmap of [width] × the page's natural aspect-ratio height,
     * applying [rotationDegrees] (0/90/180/270) by post-rotating the bitmap.
     */
    fun renderPage(pageIndex: Int, width: Int, rotationDegrees: Int = 0): Bitmap {
        val r = renderer ?: error("no document open")
        val page = r.openPage(pageIndex)
        try {
            val height = (width.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
            val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            raw.eraseColor(Color.WHITE)
            page.render(raw, null, null, AndroidPdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            if (rotationDegrees % 360 == 0) return raw
            val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (rotated !== raw) raw.recycle()
            return rotated
        } finally {
            page.close()
        }
    }

    fun bookmarks(): List<PdfBookmark> = bookmarksCache

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

    fun close() {
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
        sourceFile = null
        bookmarksCache = emptyList()
    }
}
