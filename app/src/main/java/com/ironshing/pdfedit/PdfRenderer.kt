package com.ironshing.pdfedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File

/**
 * Thin wrapper over PdfiumCore. Owns a single open document.
 * Call [open] then [renderPage] / [pageCount]. [close] when done.
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
     * Render [pageIndex] into a bitmap of [width] × the page's natural aspect-ratio height.
     */
    fun renderPage(pageIndex: Int, width: Int): Bitmap {
        val d = doc ?: error("no document open")
        core.openPage(d, pageIndex)
        val pageW = core.getPageWidthPoint(d, pageIndex)
        val pageH = core.getPageHeightPoint(d, pageIndex)
        val height = (width.toLong() * pageH / pageW).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        core.renderPageBitmap(d, bmp, pageIndex, 0, 0, width, height, true)
        return bmp
    }

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
