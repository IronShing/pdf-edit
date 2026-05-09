package com.ironshing.pdfedit

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

    DisposableEffect(file) {
        try {
            renderer.open(file)
            pageCount = renderer.pageCount
            bookmarks = renderer.bookmarks()
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        onDispose { renderer.close() }
    }

    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val currentPage by remember {
        derivedStateOf {
            (listState.firstVisibleItemIndex + 1).coerceAtMost(pageCount.coerceAtLeast(1))
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Bookmarks",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                if (bookmarks.isEmpty()) {
                    Text(
                        "This PDF has no bookmarks.",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    BookmarkList(
                        items = bookmarks,
                        depth = 0,
                        onSelect = { pageIdx ->
                            scope.launch {
                                drawerState.close()
                                listState.scrollToItem(pageIdx.toInt().coerceIn(0, pageCount - 1))
                            }
                        }
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(file.nameWithoutExtension.take(30)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                        }
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Bookmarks")
                        }
                        IconButton(onClick = {
                            val idx = listState.firstVisibleItemIndex
                            val cur = rotations[idx] ?: 0
                            rotations[idx] = (cur + 90) % 360
                        }) {
                            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate page")
                        }
                    }
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
                                .background(Color(0xFF202020)),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(8.dp)
                        ) {
                            items(
                                items = (0 until pageCount).toList(),
                                key = { it }
                            ) { pageIndex ->
                                PageView(
                                    renderer = renderer,
                                    pageIndex = pageIndex,
                                    widthPx = pxWidth,
                                    rotation = rotations[pageIndex] ?: 0
                                )
                            }
                        }
                        if (pageCount > 0) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp),
                                shape = MaterialTheme.shapes.small,
                                tonalElevation = 4.dp,
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
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkList(
    items: List<PdfBookmark>,
    depth: Int,
    onSelect: (Long) -> Unit
) {
    items.forEach { bm ->
        NavigationDrawerItem(
            label = { Text("${"  ".repeat(depth)}${bm.title}", maxLines = 2) },
            selected = false,
            onClick = { onSelect(bm.pageIndex) },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
        if (bm.children.isNotEmpty()) {
            BookmarkList(items = bm.children, depth = depth + 1, onSelect = onSelect)
        }
    }
}

@Composable
private fun PageView(
    renderer: PdfRenderer,
    pageIndex: Int,
    widthPx: Int,
    rotation: Int
) {
    var bitmap by remember(pageIndex, widthPx, rotation) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(pageIndex, widthPx, rotation) {
        bitmap = withContext(Dispatchers.IO) {
            renderer.renderPage(pageIndex, widthPx, rotation)
        }
    }

    val bmp = bitmap
    if (bmp == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.LightGray),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(32.dp))
        }
    } else {
        var scale by remember(pageIndex) { mutableStateOf(1f) }
        var offsetX by remember(pageIndex) { mutableStateOf(0f) }
        var offsetY by remember(pageIndex) { mutableStateOf(0f) }
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
                .pointerInput(pageIndex) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 6f)
                        if (newScale != scale) {
                            scale = newScale
                        }
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
        )
    }
}
