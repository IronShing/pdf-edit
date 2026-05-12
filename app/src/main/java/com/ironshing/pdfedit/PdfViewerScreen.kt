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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
    /** zoom scale per page; lifted up so back-handler and re-render can react */
    val pageScales = remember { mutableStateMapOf<Int, Float>() }
    val pageOffsets = remember { mutableStateMapOf<Int, Offset>() }
    /** text-edit mode: which page (null = view), runs found on it, currently selected run */
    var textEditTarget by remember { mutableStateOf<Int?>(null) }
    var textRuns by remember { mutableStateOf<List<TextRun>>(emptyList()) }
    var pagePdfSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var editingRun by remember { mutableStateOf<TextRun?>(null) }
    var editingDraft by remember { mutableStateOf("") }

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

    BackHandler(enabled = !saving) {
        val visible = listState.firstVisibleItemIndex
        when {
            editingRun != null -> {
                editingRun = null
                editingDraft = ""
            }
            textEditTarget != null -> {
                textEditTarget = null
                textRuns = emptyList()
            }
            drawTarget != null -> {
                drawSession.clear()
                drawTarget = null
            }
            (pageScales[visible] ?: 1f) > 1f -> {
                pageScales[visible] = 1f
                pageOffsets[visible] = Offset.Zero
            }
            else -> onClose()
        }
    }

    // When the user enters text-edit mode (or after a save) extract runs from disk.
    LaunchedEffect(textEditTarget, renderEpoch) {
        val target = textEditTarget
        if (target == null) {
            textRuns = emptyList()
            pagePdfSize = null
        } else {
            val pair = withContext(Dispatchers.IO) {
                runCatching {
                    val size = renderer.pagePdfSize(target)
                    val runs = renderer.extractTextRuns(target)
                    size to runs
                }.getOrNull()
            }
            if (pair != null) {
                pagePdfSize = pair.first
                textRuns = pair.second
            } else {
                textRuns = emptyList()
                pagePdfSize = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (pageCount > 0) {
                            val mode = when {
                                drawTarget != null -> "draw"
                                textEditTarget != null -> "edit text"
                                else -> "view"
                            }
                            "${file.nameWithoutExtension.take(18)}  ·  $currentPage / $pageCount  ·  $mode"
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
                    if (drawTarget == null && textEditTarget == null) {
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
                        IconButton(onClick = {
                            textEditTarget = listState.firstVisibleItemIndex
                        }) {
                            Icon(Icons.Default.TextFields, contentDescription = "Edit text on this page")
                        }
                    } else if (textEditTarget != null) {
                        IconButton(onClick = {
                            textEditTarget = null
                            textRuns = emptyList()
                        }, enabled = !saving) {
                            Icon(Icons.Default.Close, contentDescription = "Exit text-edit")
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
                        userScrollEnabled = drawTarget == null && textEditTarget == null
                    ) {
                        items(
                            items = (0 until pageCount).toList(),
                            key = { it }
                        ) { pageIndex ->
                            val isTextTarget = textEditTarget == pageIndex
                            PageView(
                                renderer = renderer,
                                pageIndex = pageIndex,
                                widthPx = pxWidth,
                                rotation = rotations[pageIndex] ?: 0,
                                renderEpoch = renderEpoch,
                                drawing = drawTarget == pageIndex,
                                drawSession = drawSession,
                                scale = if (isTextTarget) 1f else (pageScales[pageIndex] ?: 1f),
                                offset = if (isTextTarget) Offset.Zero else (pageOffsets[pageIndex] ?: Offset.Zero),
                                onScaleChange = { pageScales[pageIndex] = it },
                                onOffsetChange = { pageOffsets[pageIndex] = it },
                                textEditing = isTextTarget,
                                textRuns = if (isTextTarget) textRuns else emptyList(),
                                pagePdfSize = if (isTextTarget) pagePdfSize else null,
                                onRunTap = { run ->
                                    editingRun = run
                                    editingDraft = run.text
                                },
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

    val activeRun = editingRun
    if (activeRun != null) {
        TextEditDialog(
            original = activeRun,
            draft = editingDraft,
            onDraftChange = { editingDraft = it },
            onCancel = {
                editingRun = null
                editingDraft = ""
            },
            onConfirm = {
                val pending = activeRun
                val pendingDraft = editingDraft
                saving = true
                editingRun = null
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching { renderer.replaceText(pending, pendingDraft) }
                    }
                    outcome.onFailure { error = it.message }
                    editingDraft = ""
                    saving = false
                    renderEpoch++
                }
            },
            saving = saving
        )
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

@Composable
private fun TextEditDialog(
    original: TextRun,
    draft: String,
    onDraftChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    saving: Boolean
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text("Edit text") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    text = "Original: ${original.text}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    singleLine = true
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Font: ${original.fontName} · ${"%.1f".format(original.fontSize)}pt\n" +
                            "Alpha caveat: new text is drawn in Helvetica over a white rectangle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !saving && draft.isNotEmpty()) {
                Text(if (saving) "Saving..." else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") }
        }
    )
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
    scale: Float,
    offset: Offset,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    textEditing: Boolean,
    textRuns: List<TextRun>,
    pagePdfSize: Pair<Int, Int>?,
    onRunTap: (TextRun) -> Unit,
    onRendered: (IntSize) -> Unit
) {
    /** integer render quality tier: 1, 2, or 4 — re-rendered at widthPx × quality on zoom settle */
    var renderQuality by remember(pageIndex) { mutableStateOf(1) }
    var bitmap by remember(pageIndex, widthPx, rotation, renderEpoch, renderQuality) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(pageIndex, widthPx, rotation, renderEpoch, renderQuality) {
        bitmap = withContext(Dispatchers.IO) {
            renderer.renderPage(pageIndex, widthPx * renderQuality, rotation)
        }
    }

    // Settle the zoom level then bump render quality to the nearest integer step.
    // Debounce so we don't re-render on every pinch frame.
    LaunchedEffect(scale, pageIndex) {
        kotlinx.coroutines.delay(180)
        val target = when {
            scale <= 1.25f -> 1
            scale <= 2.5f -> 2
            else -> 4
        }
        if (target != renderQuality) renderQuality = target
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
        LaunchedEffect(bmp.width, bmp.height, renderQuality) {
            // report the displayed bitmap size in pixels at quality=1 so PDF-coord transforms stay
            // consistent regardless of current render quality
            onRendered(IntSize(bmp.width / renderQuality, bmp.height / renderQuality))
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .then(
                        if (drawing || textEditing) Modifier
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
                                                val newScale = (scale * zoomChange).coerceIn(1f, 6f)
                                                onScaleChange(newScale)
                                                if (newScale == 1f) onOffsetChange(Offset.Zero)
                                            }
                                            if (scale > 1f && panChange != Offset.Zero) {
                                                onOffsetChange(offset + panChange)
                                            }
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                        pressed == 1 && scale > 1f -> {
                                            val panChange = event.calculatePan()
                                            if (panChange != Offset.Zero) {
                                                onOffsetChange(offset + panChange)
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
            if (textEditing && pagePdfSize != null && textRuns.isNotEmpty()) {
                val density = LocalDensity.current
                val bmpWidthPx = bmp.width / renderQuality
                val bmpHeightPx = bmp.height / renderQuality
                val (pdfW, pdfH) = pagePdfSize
                val sx = bmpWidthPx.toFloat() / pdfW
                val sy = bmpHeightPx.toFloat() / pdfH
                for (run in textRuns) {
                    val leftPx = run.x * sx
                    val topPx = (pdfH - run.baselineY - run.height) * sy
                    val widthPxRun = run.width * sx
                    val heightPxRun = run.height * sy
                    Box(
                        modifier = Modifier
                            .offset(
                                x = with(density) { leftPx.toDp() },
                                y = with(density) { topPx.toDp() }
                            )
                            .size(
                                width = with(density) { widthPxRun.toDp() },
                                height = with(density) { heightPxRun.toDp() }
                            )
                            .border(1.dp, Color(0x9900AAFF))
                            .background(Color(0x3300AAFF))
                            .clickable { onRunTap(run) }
                    )
                }
            }
        }
    }
}
