package com.ironshing.pdfedit

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loadedFile by remember { mutableStateOf<File?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val pickPdf = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val cached = withContext(Dispatchers.IO) {
                    val out = File(context.cacheDir, "open_${System.currentTimeMillis()}.pdf")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(out).use { input.copyTo(it) }
                    } ?: error("could not open input stream for $uri")
                    out
                }
                loadError = null
                loadedFile = cached
            } catch (e: Exception) {
                Log.e("PdfEdit", "open failed for $uri", e)
                loadError = e.message ?: e.javaClass.simpleName
                loadedFile = null
            }
        }
    }

    val current = loadedFile
    if (current == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PDF Edit — v0.0.1", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                Text("Open a PDF to begin", modifier = Modifier.padding(top = 8.dp))
                Button(
                    onClick = { pickPdf.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Open PDF")
                }
                loadError?.let { msg ->
                    Text(
                        text = "Error: $msg",
                        modifier = Modifier.padding(top = 16.dp),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    } else {
        PdfViewerScreen(
            file = current,
            onClose = { loadedFile = null }
        )
    }
}
