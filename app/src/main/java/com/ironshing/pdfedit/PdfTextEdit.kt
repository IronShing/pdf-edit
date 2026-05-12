package com.ironshing.pdfedit

/**
 * One edit-able run of text on a page, with PDF-coord bounding box.
 *
 * Coords are in PDF points (1/72 inch), origin bottom-left:
 *   - [x], [y] = lower-left corner of the run's bounding box
 *   - [width], [height] = run dimensions
 *
 * [baselineY] is the text baseline, also in PDF-native coords. Used when redrawing.
 */
data class TextRun(
    val pageIndex: Int,
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val baselineY: Float,
    val fontName: String,
    val fontSize: Float
)
