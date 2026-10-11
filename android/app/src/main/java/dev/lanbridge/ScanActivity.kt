@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.lanbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScanActivity : ComponentActivity() {
    private var allowed by mutableStateOf(false)
    private var message by mutableStateOf("将电脑上的二维码放入画面，识别后自动连接。")
    private var flashAvailable by mutableStateOf(false)
    private var torch by mutableStateOf(false)
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val completed = AtomicBoolean(false)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        if(!granted) message = "需要相机权限才能扫码，也可以返回手动连接。"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        allowed = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        setContent {
            val colors = if(isSystemInDarkTheme()) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialExpressiveTheme(colorScheme = colors) {
                Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("扫描电脑二维码") }, navigationIcon = { TextButton(onClick = { finish() }) { Text("返回") } }) }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        if(allowed) {
                            AndroidView(factory = { context -> PreviewView(context).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                startCamera(this)
                            } }, modifier = Modifier.fillMaxSize())
                            Surface(modifier = Modifier.align(Alignment.Center).size(260.dp), color = Color.Transparent, shape = RoundedCornerShape(28.dp), border = BorderStroke(3.dp, Color.White)) {}
                        }
                        Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(message, style = MaterialTheme.typography.bodyLarge)
                                if(!allowed) {
                                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) { Text("允许使用相机") }
                                    TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) { Text("打开应用权限设置") }
                                } else if(flashAvailable) {
                                    FilledTonalButton(onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) }) { Text(if(torch) "关闭补光" else "打开补光") }
                                }
                                TextButton(onClick = { finish() }) { Text("返回手动连接") }
                            }
                        }
                    }
                }
            }
        }
        if(!allowed) permission.launch(Manifest.permission.CAMERA)
    }
    override fun onResume() {
        super.onResume()
        allowed = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    private fun startCamera(view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if(isDestroyed || isFinishing) return@addListener
            try {
                val activeProvider = future.get(); provider = activeProvider
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                    .build()
                analysis = analyzer
                var previous = 0L
                analyzer.setAnalyzer(executor) { image ->
                    try {
                        val now = SystemClock.elapsedRealtime()
                        if(!completed.get() && now-previous >= 180) {
                            previous = now
                            val plane = image.planes[0]
                            val luma = QrDecoder.copyLuma(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
                            QrDecoder.decode(luma, image.width, image.height)?.let { value ->
                                try {
                                    Pairing.fromQr(value)
                                    if(completed.compareAndSet(false, true)) mainExecutor.execute {
                                        if(!isDestroyed && !isFinishing) { setResult(RESULT_OK, Intent().putExtra(RESULT_PAIRING, value)); finish() }
                                    }
                                } catch(_: Exception) { mainExecutor.execute { message = "这不是有效的 LanBridge 配对二维码，请扫描电脑上的二维码。" } }
                            }
                        }
                    } catch(_: Exception) { /* A dropped or incomplete camera frame can be retried. */ }
                    finally { image.close() }
                }
                val selector = if(activeProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                activeProvider.unbindAll()
                camera = activeProvider.bindToLifecycle(this, selector, preview, analyzer)
                flashAvailable = camera?.cameraInfo?.hasFlashUnit() == true
            } catch(_: Exception) { message = "相机无法启动，请检查权限，或返回使用手动连接。" }
        }, mainExecutor)
    }
    override fun onDestroy() {
        completed.set(true); analysis?.clearAnalyzer(); provider?.unbindAll(); executor.shutdown()
        super.onDestroy()
    }
    companion object { const val RESULT_PAIRING = "lanbridge_pairing" }
}
