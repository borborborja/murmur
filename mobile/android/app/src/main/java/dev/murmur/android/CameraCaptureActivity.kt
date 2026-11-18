package dev.murmur.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dev.murmur.android.ui.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** A camera-only session: no microphone or video use case can interrupt the recording service. */
class CameraCaptureActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var previewView: PreviewView
    private var capturing by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var lastScan = 0L
    private val scanQr get() = intent.getBooleanExtra("scanQr", false)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) bindCamera() else { setResult(RESULT_CANCELED); finish() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        setContent {
            MurmurTheme(AppSettings(this).read()) {
                Scaffold(topBar = { TopAppBar(title = { Text(tr(if (scanQr) "scan_qr" else "photo")) }, navigationIcon = {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("back")) }
                }) }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                        if (scanQr) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp), color = LocalPalette.current.background, shape = MaterialTheme.shapes.medium) {
                            Text(tr("pair_hint"), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        else FilledIconButton(onClick = { capture() }, enabled = !capturing, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp).size(76.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = LocalPalette.current.accent, contentColor = LocalPalette.current.onAccent)) {
                            if (capturing) CircularProgressIndicator(Modifier.size(28.dp), color = LocalPalette.current.onAccent) else Icon(Icons.Filled.PhotoCamera, tr("photo"), Modifier.size(32.dp))
                        }
                        if (failed) Surface(Modifier.align(Alignment.TopCenter).padding(20.dp), color = LocalPalette.current.background, shape = MaterialTheme.shapes.medium) { Text(tr("operation_failed"), Modifier.padding(16.dp)) }
                    }
                }
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    @Suppress("DEPRECATION")
    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
                provider.unbindAll()
                if (scanQr) {
                    val analysis = ImageAnalysis.Builder().setTargetResolution(Size(640, 480)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    val reader = QRCodeReader()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            if (System.currentTimeMillis() - lastScan < 250) return@setAnalyzer
                            lastScan = System.currentTimeMillis()
                            val plane = image.planes[0]
                            val buffer = plane.buffer.duplicate()
                            val data = ByteArray(image.width * image.height)
                            val row = ByteArray(plane.rowStride)
                            for (y in 0 until image.height) {
                                buffer.position(y * plane.rowStride)
                                val count = minOf(plane.rowStride, buffer.remaining())
                                buffer.get(row, 0, count)
                                for (x in 0 until image.width) data[y * image.width + x] = row[x * plane.pixelStride]
                            }
                            val source = PlanarYUVLuminanceSource(data, image.width, image.height, 0, 0, image.width, image.height, false)
                            val result = runCatching { reader.decode(BinaryBitmap(HybridBinarizer(source))) }.getOrNull()
                            if (result != null) runOnUiThread {
                                if (result.text.startsWith("murmur://pair?")) {
                                    setResult(RESULT_OK, Intent().putExtra("uri", result.text).putExtra("pairingUri", result.text)); finish()
                                }
                            }
                        } finally { reader.reset(); image.close() }
                    }
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } else {
                    imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                }
            }.onFailure { failed = true }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val camera = imageCapture ?: return
        val destination = intent.getStringExtra("outputUri")?.let(Uri::parse) ?: return
        capturing = true
        val temporary = File.createTempFile("murmur-photo-", ".jpg", cacheDir)
        camera.takePicture(ImageCapture.OutputFileOptions.Builder(temporary).build(), executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                runCatching {
                    contentResolver.openOutputStream(destination, "w").use { output ->
                        requireNotNull(output)
                        temporary.inputStream().use { it.copyTo(output) }
                    }
                }.onSuccess { runOnUiThread { setResult(RESULT_OK, Intent().putExtra("outputUri", destination.toString())); finish() } }
                    .onFailure { runOnUiThread { capturing = false; failed = true } }
                temporary.delete()
            }
            override fun onError(error: ImageCaptureException) { temporary.delete(); runOnUiThread { capturing = false; failed = true } }
        })
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        executor.shutdown()
        super.onDestroy()
    }
}
