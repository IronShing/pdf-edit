package com.ironshing.pdfedit

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    /** Transient save errors — shown as a snackbar-ish overlay so the viewer stays mounted. */
    var saveError by remember { mutableStateOf<String?>(null) }
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
    /** text-edit mode: which page (null = view), runs found on it, run being edited inline */
    var textEditTarget by remember { mutableStateOf<Int?>(null) }
    var textRuns by remember { mutableStateOf<List<TextRun>>(emptyList()) }
    var pagePdfSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var activeRun by remember { mutableStateOf<TextRun?>(null) }
    var activeDraft by remember { mutableStateOf("") }

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
            activeRun != null -> {
                activeRun = null
                activeDraft = ""
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

    fun commitActiveEdit() {
        val pending = activeRun ?: return
        val pendingDraft = activeDraft
        activeRun = null
        if (pendingDraft == pending.text || pendingDraft.isEmpty()) {
            activeDraft = ""
            return
        }
        saving = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { renderer.replaceText(pending, pendingDraft) }
            }
            outcome.onFailure { saveError = it.message ?: it.javaClass.simpleName }
            activeDraft = ""
            saving = false
            renderEpoch++
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
                                    outcome.onFailure { saveError = it.message ?: it.javaClass.simpleName }
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
                            // Cap bitmap render width at 720 to keep memory bounded
                            // even on big screens — most PDFs look fine here, sharper
                            // version kicks in when the user zooms.
                            val cappedWidthPx = pxWidth.coerceAtMost(720)
                            val isFocused = pageIndex == listState.firstVisibleItemIndex
                            PageView(
                                renderer = renderer,
                                pageIndex = pageIndex,
                                widthPx = cappedWidthPx,
                                rotation = rotations[pageIndex] ?: 0,
                                renderEpoch = renderEpoch,
                                drawing = drawTarget == pageIndex,
                                drawSession = drawSession,
                                scrolling = listState.isScrollInProgress && !isFocused,
                                scale = pageScales[pageIndex] ?: 1f,
                                offset = pageOffsets[pageIndex] ?: Offset.Zero,
                                onScaleChange = { pageScales[pageIndex] = it },
                                onOffsetChange = { pageOffsets[pageIndex] = it },
                                textEditing = isTextTarget,
                                textRuns = if (isTextTarget) textRuns else emptyList(),
                                pagePdfSize = if (isTextTarget) pagePdfSize else null,
                                activeRun = if (isTextTarget) activeRun else null,
                                activeDraft = activeDraft,
                                onActivateRun = { run ->
                                    if (activeRun != null && activeRun != run) commitActiveEdit()
                                    activeRun = run
                                    activeDraft = run.text
                                },
                                onDraftChange = { activeDraft = it },
                                onCommitEdit = { commitActiveEdit() },
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
                    saveError?.let { msg ->
                        LaunchedEffect(msg) {
                            kotlinx.coroutines.delay(4000)
                            saveError = null
                        }
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                                .fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = Color(0xEE5A1A1A)
                        ) {
                            Text(
                                text = "Save failed: $msg",
                                color = Color.White,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
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
    scrolling: Boolean,
    scale: Float,
    offset: Offset,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    textEditing: Boolean,
    textRuns: List<TextRun>,
    pagePdfSize: Pair<Int, Int>?,
    activeRun: TextRun?,
    activeDraft: String,
    onActivateRun: (TextRun) -> Unit,
    onDraftChange: (String) -> Unit,
    onCommitEdit: () -> Unit,
    onRendered: (IntSize) -> Unit
) {
    /**
     * Effective render width as a fraction of the screen pixel width.
     *  0.5 = render at half width (used while scrolling — fast, mildly blurry)
     *  1.0 = normal idle resolution
     *  2.0 = zoomed-in resolution
     */
    var renderScale by remember(pageIndex) { mutableStateOf(1.0f) }
    var bitmap by remember(pageIndex, widthPx, rotation, renderEpoch, renderScale) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(pageIndex, widthPx, rotation, renderEpoch, renderScale) {
        val targetWidth = (widthPx * renderScale).toInt().coerceAtLeast(1)
        bitmap = withContext(Dispatchers.IO) {
            renderer.renderPage(pageIndex, targetWidth, rotation)
        }
    }

    // Render quality strategy:
    //  - while scrolling: don't trigger any new renders, keep whatever bitmap is
    //    already on screen. Renders are synchronous through a lock and stale ones
    //    can't be cancelled mid-flight, so issuing one per scroll-stop avoids
    //    queuing up work that blocks fresh pages from rendering.
    //  - after scroll settles + no zoom: 1× (sharp at base size)
    //  - after zoom > 1.25× settles: 2× (sharp when zoomed)
    LaunchedEffect(scale, pageIndex, scrolling) {
        if (scrolling) return@LaunchedEffect
        kotlinx.coroutines.delay(220)
        val target = if (scale <= 1.25f) 1.0f else 2.0f
        if (target != renderScale) renderScale = target
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
        LaunchedEffect(bmp.width, bmp.height, renderScale) {
            // report the displayed bitmap size in screen pixels (== widthPx at renderScale=1.0),
            // independent of current render quality, so PDF-coord transforms stay consistent
            val factor = if (renderScale > 0f) renderScale else 1f
            onRendered(IntSize((bmp.width / factor).toInt(), (bmp.height / factor).toInt()))
        }

        // Only allocate an offscreen graphicsLayer when there's an actual transform —
        // wrapping every page in a layer at identity scale is wasted GPU work.
        // When zoomed, use the lambda form so pan updates read state in the DRAW
        // phase instead of triggering a recomposition every finger-move frame.
        val hasTransform = scale != 1f || offset != Offset.Zero
        val scaleLive by rememberUpdatedState(scale)
        val offsetLive by rememberUpdatedState(offset)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (hasTransform) Modifier.graphicsLayer {
                        scaleX = scaleLive
                        scaleY = scaleLive
                        translationX = offsetLive.x
                        translationY = offsetLive.y
                    } else Modifier
                )
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (drawing || textEditing) Modifier
                        else {
                            // pointerInput captures closure values at launch time; without
                            // rememberUpdatedState the lambda sees scale=1f forever and zoom
                            // never accumulates.
                            val scaleLive by rememberUpdatedState(scale)
                            val offsetLive by rememberUpdatedState(offset)
                            val onScaleLive by rememberUpdatedState(onScaleChange)
                            val onOffsetLive by rememberUpdatedState(onOffsetChange)
                            Modifier.pointerInput(pageIndex) {
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
                                                    val newScale = (scaleLive * zoomChange).coerceIn(1f, 6f)
                                                    onScaleLive(newScale)
                                                    if (newScale == 1f) onOffsetLive(Offset.Zero)
                                                }
                                                if (scaleLive > 1f && panChange != Offset.Zero) {
                                                    onOffsetLive(offsetLive + panChange)
                                                }
                                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                            }
                                            pressed == 1 && scaleLive > 1f -> {
                                                val panChange = event.calculatePan()
                                                if (panChange != Offset.Zero) {
                                                    onOffsetLive(offsetLive + panChange)
                                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                                }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
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
                val factor = if (renderScale > 0f) renderScale else 1f
                val bmpWidthPx = (bmp.width / factor).toInt()
                val bmpHeightPx = (bmp.height / factor).toInt()
                val (pdfW, pdfH) = pagePdfSize
                val sx = bmpWidthPx.toFloat() / pdfW
                val sy = bmpHeightPx.toFloat() / pdfH

                fun runScreenRect(run: TextRun): RunRect {
                    val visualHeight = run.fontSize * 1.2f
                    val descent = run.fontSize * 0.25f
                    val pdfTop = run.baselineY + (visualHeight - descent)
                    return RunRect(
                        leftPx = run.x * sx,
                        topPx = (pdfH - pdfTop) * sy,
                        widthPx = run.width * sx,
                        heightPx = visualHeight * sy
                    )
                }

                // Full-page transparent tap layer: hit-test tap location against
                // textRuns and activate the one under the finger. Generous vertical
                // padding (≈ half a line) and a few pixels of horizontal slack so
                // narrow runs (single words) don't require finger-precision taps.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(textRuns) {
                            detectTapGestures { tap ->
                                val hit = textRuns.firstOrNull { run ->
                                    val r = runScreenRect(run)
                                    val padY = r.heightPx * 0.45f
                                    val padX = 6f
                                    tap.x in (r.leftPx - padX)..(r.leftPx + r.widthPx + padX) &&
                                        tap.y in (r.topPx - padY)..(r.topPx + r.heightPx + padY)
                                }
                                if (hit != null) onActivateRun(hit) else onCommitEdit()
                            }
                        }
                )

                // Inline editor for the active run.
                if (activeRun != null && activeRun.pageIndex == pageIndex) {
                    val r = runScreenRect(activeRun)
                    val focusRequester = remember(activeRun) { FocusRequester() }
                    LaunchedEffect(activeRun) { focusRequester.requestFocus() }
                    BasicTextField(
                        value = activeDraft,
                        onValueChange = onDraftChange,
                        modifier = Modifier
                            .offset(
                                x = with(density) { r.leftPx.toDp() },
                                y = with(density) { r.topPx.toDp() }
                            )
                            .size(
                                width = with(density) {
                                    // Give the field some slack to grow as the user types
                                    (r.widthPx * 1.5f).coerceAtLeast(r.widthPx + 80f).toDp()
                                },
                                height = with(density) { (r.heightPx + 8f).toDp() }
                            )
                            .background(Color.White)
                            .border(1.dp, Color(0xFF2563EB))
                            .padding(horizontal = 2.dp)
                            .focusRequester(focusRequester),
                        textStyle = TextStyle(
                            color = Color.Black,
                            fontSize = with(density) { (activeRun.fontSize * sx).toSp() },
                            fontFamily = FontFamily.SansSerif
                        ),
                        cursorBrush = SolidColor(Color(0xFF2563EB)),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onCommitEdit() })
                    )
                }
            }
        }
    }
}

private data class RunRect(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float
)
