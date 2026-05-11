package com.ironshing.pdfedit

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val renderer = remember { PdfRenderer(context) }
    var pageCount by remember { mutableStateOf(0) }
    var bookmarks by remember { mutableStateOf<List<PdfBookmark>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val rotations = remember { mutableStateMapOf<Int, Int>() }
    /** rendered bitmap dimensions per page index, used for screen→PDF coord transform on save */
    val renderedSizes = remember { mutableStateMapOf<Int, IntSize>() }
    /** bump to invalidate page bitmaps after a save */
    var renderEpoch by remember { mutableStateOf(0) }
    /** drawing state: target page + accumulated strokes; null when in view mode */
    var drawTarget by remember { mutableStateOf<Int?>(null) }
    val drawSession = remember { DrawSession() }
    var saving by remember { mutableStateOf(false) }

    DisposableEffect(file) {
        try {
            renderer.open(file)
            pageCount = renderer.pageCount
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        onDispose { renderer.close() }
    }

    LaunchedEffect(file) {
        bookmarks = withContext(Dispatchers.IO) {
            runCatching { renderer.loadBookmarks() }.getOrDefault(emptyList())
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var bookmarksOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    val currentPage by remember {
        derivedStateOf {
            (listState.firstVisibleItemIndex + 1).coerceAtMost(pageCount.coerceAtLeast(1))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (pageCount > 0) {
                            val mode = if (drawTarget != null) "draw" else "view"
                            "${file.nameWithoutExtension.take(20)}  ·  $currentPage / $pageCount  ·  $mode"
                        } else {
                            file.nameWithoutExtension.take(30)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                },
                actions = {
                    if (drawTarget == null) {
                        IconButton(onClick = { bookmarksOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Bookmarks")
                        }
                        IconButton(onClick = {
                            val idx = listState.firstVisibleItemIndex
                            val cur = rotations[idx] ?: 0
                            rotations[idx] = (cur + 90) % 360
                        }) {
                            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate page")
                        }
                        IconButton(onClick = {
                            drawSession.clear()
                            drawTarget = listState.firstVisibleItemIndex
                        }) {
                            Icon(Icons.Default.Draw, contentDescription = "Draw on this page")
                        }
                    } else {
                        IconButton(
                            onClick = {
                                drawSession.clear()
                                drawTarget = null
                            },
                            enabled = !saving
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel drawing")
                        }
                        IconButton(
                            onClick = {
                                val target = drawTarget ?: return@IconButton
                                if (drawSession.isEmpty()) {
                                    drawTarget = null
                                    return@IconButton
                                }
                                val bitmapSize = renderedSizes[target] ?: return@IconButton
                                saving = true
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val (pdfW, pdfH) = renderer.pagePdfSize(target)
                                            val strokesPdf = drawSession.strokes.mapNotNull { stroke ->
                                                if (stroke.size < 2) null
                                                else stroke.map { p ->
                                                    floatArrayOf(
                                                        p.x * pdfW / bitmapSize.width,
                                                        pdfH - (p.y * pdfH / bitmapSize.height)
                                                    )
                                                }
                                            }
                                            renderer.burnStrokesToPage(target, strokesPdf)
                                            pageCount = renderer.pageCount
                                        }
                                    }
                                    outcome.onFailure { error = it.message }
                                    drawSession.clear()
                                    drawTarget = null
                                    renderEpoch++
                                    saving = false
                                }
                            },
                            enabled = !saving
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Save drawing")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                error != null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Failed to open: $error")
                }
                pageCount == 0 -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                else -> {
                    val configuration = LocalConfiguration.current
                    val density = LocalDensity.current
                    val pxWidth = with(density) { configuration.screenWidthDp.dp.toPx().toInt() }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0A0A0A)),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(8.dp),
                        userScrollEnabled = drawTarget == null
                    ) {
                        items(
                            items = (0 until pageCount).toList(),
                            key = { it }
                        ) { pageIndex ->
                            PageView(
                                renderer = renderer,
                                pageIndex = pageIndex,
                                widthPx = pxWidth,
                                rotation = rotations[pageIndex] ?: 0,
                                renderEpoch = renderEpoch,
                                drawing = drawTarget == pageIndex,
                                drawSession = drawSession,
                                onRendered = { size -> renderedSizes[pageIndex] = size }
                            )
                        }
                    }
                    if (pageCount > 0) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                            shape = MaterialTheme.shapes.small,
                            color = Color(0xCC000000)
                        ) {
                            Text(
                                text = "$currentPage / $pageCount",
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    if (saving) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xAA000000)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    if (bookmarksOpen) {
        ModalBottomSheet(
            onDismissRequest = { bookmarksOpen = false },
            sheetState = sheetState
        ) {
            Text(
                "Bookmarks",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (bookmarks.isEmpty()) {
                Text(
                    "This PDF has no bookmarks.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    items(items = bookmarks.flatten(), key = { it.hashCode() }) { entry ->
                        TextButton(
                            onClick = {
                                bookmarksOpen = false
                                scope.launch {
                                    listState.scrollToItem(
                                        entry.bookmark.pageIndex.toInt().coerceIn(0, pageCount - 1)
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (16 + entry.depth * 16).dp, end = 16.dp)
                        ) {
                            Text(
                                text = entry.bookmark.title.ifBlank { "(untitled)" },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class FlatBookmark(val bookmark: PdfBookmark, val depth: Int)

private fun List<PdfBookmark>.flatten(depth: Int = 0): List<FlatBookmark> =
    flatMap { listOf(FlatBookmark(it, depth)) + it.children.flatten(depth + 1) }

@Composable
private fun PageView(
    renderer: PdfRenderer,
    pageIndex: Int,
    widthPx: Int,
    rotation: Int,
    renderEpoch: Int,
    drawing: Boolean,
    drawSession: DrawSession,
    onRendered: (IntSize) -> Unit
) {
    var bitmap by remember(pageIndex, widthPx, rotation, renderEpoch) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(pageIndex, widthPx, rotation, renderEpoch) {
        bitmap = withContext(Dispatchers.IO) { renderer.renderPage(pageIndex, widthPx, rotation) }
    }

    val bmp = bitmap
    if (bmp == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF222222)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(32.dp))
        }
    } else {
        LaunchedEffect(bmp.width, bmp.height) {
            onRendered(IntSize(bmp.width, bmp.height))
        }
        var scale by remember(pageIndex) { mutableStateOf(1f) }
        var offsetX by remember(pageIndex) { mutableStateOf(0f) }
        var offsetY by remember(pageIndex) { mutableStateOf(0f) }

        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .then(
                        if (drawing) Modifier
                        else Modifier.pointerInput(pageIndex) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.count { it.pressed }
                                    when {
                                        pressed >= 2 -> {
                                            val zoomChange = event.calculateZoom()
                                            val panChange = event.calculatePan()
                                            if (zoomChange != 1f) {
                                                scale = (scale * zoomChange).coerceIn(1f, 6f)
                                                if (scale == 1f) {
                                                    offsetX = 0f; offsetY = 0f
                                                }
                                            }
                                            if (scale > 1f && panChange != Offset.Zero) {
                                                offsetX += panChange.x
                                                offsetY += panChange.y
                                            }
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                        pressed == 1 && scale > 1f -> {
                                            val panChange = event.calculatePan()
                                            if (panChange != Offset.Zero) {
                                                offsetX += panChange.x
                                                offsetY += panChange.y
                                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                            }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                    )
            )
            if (drawing) {
                DrawingOverlay(
                    session = drawSession,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    }
}
