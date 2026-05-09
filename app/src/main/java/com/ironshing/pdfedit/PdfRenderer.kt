package com.ironshing.pdfedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.ParcelFileDescriptor
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File

data class PdfBookmark(
    val title: String,
    val pageIndex: Long,
    val children: List<PdfBookmark>
)

/**
 * Thin wrapper over PdfiumCore. Owns a single open document.
 * Call [open] then [renderPage]. [close] when done.
 */
class PdfRenderer(context: Context) {
    private val core = PdfiumCore(context)
    private var pfd: ParcelFileDescriptor? = null
    private var doc: PdfDocument? = null

    fun open(file: File) {
        close()
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = fd
        doc = core.newDocument(fd)
    }

    val pageCount: Int
        get() = doc?.let { core.getPageCount(it) } ?: 0

    /**
     * Render [pageIndex] into a bitmap of [width] × the page's natural aspect-ratio height,
     * applying [rotationDegrees] (0/90/180/270) by post-rotating the bitmap.
     */
    fun renderPage(pageIndex: Int, width: Int, rotationDegrees: Int = 0): Bitmap {
        val d = doc ?: error("no document open")
        core.openPage(d, pageIndex)
        val pageW = core.getPageWidthPoint(d, pageIndex)
        val pageH = core.getPageHeightPoint(d, pageIndex)
        val height = (width.toLong() * pageH / pageW).toInt().coerceAtLeast(1)
        val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        raw.eraseColor(Color.WHITE)
        core.renderPageBitmap(d, raw, pageIndex, 0, 0, width, height, true)
        if (rotationDegrees % 360 == 0) return raw
        val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    /** Page natural size in PDF points (no rotation applied). */
    fun pageSize(pageIndex: Int): Pair<Int, Int> {
        val d = doc ?: error("no document open")
        core.openPage(d, pageIndex)
        return core.getPageWidthPoint(d, pageIndex) to core.getPageHeightPoint(d, pageIndex)
    }

    /** Bookmarks (table of contents) flattened/preserved as a tree. */
    fun bookmarks(): List<PdfBookmark> {
        val d = doc ?: return emptyList()
        return core.getTableOfContents(d).map { it.toPublic() }
    }

    private fun PdfDocument.Bookmark.toPublic(): PdfBookmark =
        PdfBookmark(
            title = title ?: "",
            pageIndex = pageIdx,
            children = children.orEmpty().map { it.toPublic() }
        )

    fun close() {
        doc?.let { core.closeDocument(it) }
        doc = null
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
    }
}
