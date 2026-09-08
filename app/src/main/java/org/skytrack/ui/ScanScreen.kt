// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - ScanScreen
// Version 1.5
// Purpose : Boarding-pass scanner. Live camera (CameraX) analysed frame by
//           frame with ZXing, plus an "from image" path for passes stored as
//           screenshots or in wallet apps. On success returns a BoardingPass
//           to the caller; the setup screen fills origin, destination and
//           flight number from it.
// =============================================================
package org.skytrack.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skytrack.R
import org.skytrack.scan.BarcodeDecoder
import org.skytrack.scan.Bcbp
import org.skytrack.scan.BoardingPass
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScanScreen(onResult: (BoardingPass) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnResult by rememberUpdatedState(onResult)

    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var status by remember { mutableStateOf<String?>(null) }
    val done = remember { AtomicBoolean(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val noBarcode = stringResource(R.string.scan_no_barcode)
    val notBcbp = stringResource(R.string.scan_not_boarding_pass)

    fun handleText(text: String?): Boolean {
        if (text == null) return false
        val bp = Bcbp.parse(text)
        if (bp == null) { status = notBcbp; return false }
        if (done.compareAndSet(false, true)) latestOnResult(bp)
        return true
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.Default) {
                loadScaledBitmap(context, uri)?.let { BarcodeDecoder.decodeBitmap(it) }
            }
            if (text == null) status = noBarcode else handleText(text)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onCancel) { Text(stringResource(R.string.back)) }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (hasCamera) {
                CameraPreview(onFrameText = { text -> if (!done.get()) handleText(text) else Unit })
            } else {
                Surface(Modifier.fillMaxSize()) {
                    Text(stringResource(R.string.scan_camera_denied), Modifier.padding(24.dp), textAlign = TextAlign.Center)
                }
            }
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.scan_from_image)) }
            if (!hasCamera) OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_grant_camera)) }
        }
    }
}

/**
 * CameraX preview bound to the composition lifecycle. Each frame's Y plane is
 * copied into a packed byte array and handed to ZXing on a single worker thread.
 * Frames arriving while a decode is in progress are dropped (KEEP_ONLY_LATEST).
 */
@Composable
private fun CameraPreview(onFrameText: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val latest by rememberUpdatedState(onFrameText)
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    try {
                        val (y, w, h) = packedLuminance(proxy)
                        val text = BarcodeDecoder.decodeLuminance(y, w, h)
                        if (text != null) previewView.post { latest(text) }
                    } finally {
                        proxy.close()
                    }
                }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (e: Exception) {
                    // Camera unavailable (emulator without camera, hardware in use); the image path still works.
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

/** Copy the Y plane of a YUV_420_888 frame into a tightly packed array. */
private fun packedLuminance(proxy: ImageProxy): Triple<ByteArray, Int, Int> {
    val plane = proxy.planes[0]
    val buf = plane.buffer
    val w = proxy.width
    val h = proxy.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val out = ByteArray(w * h)
    if (rowStride == w && pixelStride == 1) {
        buf.rewind(); buf.get(out, 0, minOf(out.size, buf.remaining()))
    } else {
        val row = ByteArray(rowStride)
        for (yy in 0 until h) {
            buf.position(yy * rowStride)
            val len = minOf(rowStride, buf.remaining())
            buf.get(row, 0, len)
            for (xx in 0 until w) out[yy * w + xx] = row[xx * pixelStride]
        }
    }
    return Triple(out, w, h)
}

/** Decode an image URI to a software bitmap no larger than ~1600 px on its long side. */
private fun loadScaledBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }
}
