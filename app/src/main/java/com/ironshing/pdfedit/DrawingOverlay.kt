package com.ironshing.pdfedit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput

class DrawSession {
    val strokes = mutableListOf<MutableList<Offset>>()
    fun isEmpty(): Boolean = strokes.all { it.size < 2 }
    fun clear() = strokes.clear()
}

@Composable
fun DrawingOverlay(
    session: DrawSession,
    color: Color = Color(0xFF000000),
    strokeWidthPx: Float = 4f,
    modifier: Modifier = Modifier
) {
    var version by remember { mutableStateOf(0) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        session.strokes.add(mutableListOf(start))
                        version++
                    },
                    onDrag = { change, _ ->
                        session.strokes.lastOrNull()?.add(change.position)
                        version++
                    },
                    onDragEnd = { version++ },
                    onDragCancel = { version++ }
                )
            }
    ) {
        // touch `version` so Compose re-invokes draw scope when strokes mutate
        @Suppress("UNUSED_EXPRESSION") version
        for (stroke in session.strokes) {
            if (stroke.size < 2) continue
            val path = Path().apply {
                moveTo(stroke[0].x, stroke[0].y)
                for (i in 1 until stroke.size) {
                    lineTo(stroke[i].x, stroke[i].y)
                }
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = strokeWidthPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}
