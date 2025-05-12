package com.example.yoloobjectdetection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.layout.onGloballyPositioned
import com.example.yoloobjectdetection.ui.theme.YoloObjectDetectionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private var hasCameraPermission by mutableStateOf(false)
    private lateinit var yoloTflite: Interpreter
    private lateinit var yoloV8Tflite: Interpreter
    private lateinit var tts: TextToSpeech
    private var capturedBitmap: Bitmap? by mutableStateOf(null)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setSpeechRate(0.9f)
            }
        }
        try {
            yoloTflite = Interpreter(loadModelFile(assets, "best (2)_float32.tflite"))
            yoloV8Tflite = Interpreter(loadModelFile(assets, "yolov8n_float32.tflite"))
            Log.d("YOLO", "YOLO Model initialized")
            Log.d("YOLOv8", "YOLOv8 Model initialized")
        } catch (e: Exception) {
            Log.e("Model", "Failed to initialize TFLite models", e)
        }

        checkCameraPermission()
        enableEdgeToEdge()
        setContent {
            YoloObjectDetectionTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (hasCameraPermission) {
                        DoorDetection(
                            modifier = Modifier.padding(innerPadding),
                            yoloTflite = yoloTflite,
                            yoloV8Tflite = yoloV8Tflite,
                            tts = tts,
                            onCapture = { bitmap ->
                                capturedBitmap = bitmap
                            },
                            capturedBitmap = capturedBitmap,
                            onShutdown = {
                                cameraExecutor.shutdown()
                                tts.stop()
                                tts.shutdown()
                                yoloTflite.close()
                                yoloV8Tflite.close()
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Camera permission is required for door and obstacle detection")
                        }
                    }
                }
            }
        }
    }

    private fun checkCameraPermission() {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun loadModelFile(assetManager: AssetManager, modelPath: String): MappedByteBuffer {
        val fileDescriptor = assetManager.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts.stop()
        tts.shutdown()
        yoloTflite.close()
        yoloV8Tflite.close()
    }
}

@Composable
fun DoorDetection(
    modifier: Modifier = Modifier,
    yoloTflite: Interpreter,
    yoloV8Tflite: Interpreter,
    tts: TextToSpeech,
    onCapture: (Bitmap) -> Unit,
    capturedBitmap: Bitmap?,
    onShutdown: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var doorDetections by remember { mutableStateOf<List<DoorDetection>>(emptyList()) }
    var obstacleDetections by remember { mutableStateOf<List<ObstacleDetection>>(emptyList()) }
    var previewSize by remember { mutableStateOf(IntSize(0, 0)) }
    var guidanceText by remember { mutableStateOf("Press Capture to detect doors and obstacles") }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var lastGuidanceTime by remember { mutableStateOf(0L) }
    val guidanceCooldown = 3000L
    var isContinuousScanningEnabled by remember { mutableStateOf(false) }
    var previousDirection by remember { mutableStateOf("") }
    var directionConsistencyCount by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            onShutdown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build()
                        .also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                    val imageCaptureInstance = ImageCapture.Builder()
                        .setTargetRotation(previewView.display.rotation)
                        .build()
                    imageCapture = imageCaptureInstance
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCaptureInstance
                        )
                    } catch (e: Exception) {
                        Log.e("CameraX", "Use case binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    previewSize = coordinates.size
                    Log.d("PreviewSize", "Preview size updated: width=${previewSize.width}, height=${previewSize.height}")
                }
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (previewSize.width > 0 && previewSize.height > 0) {
                val scaleX = size.width / previewSize.width.toFloat()
                val scaleY = size.height / previewSize.height.toFloat()
                drawLine(
                    color = Color.Yellow,
                    start = Offset(size.width / 2, size.height),
                    end = Offset(size.width / 2, 0f),
                    strokeWidth = 2f
                )
                doorDetections.forEach { detection ->
                    val rect = detection.boundingBox
                    val scaledLeft = rect.left * scaleX
                    val scaledTop = rect.top * scaleY
                    val scaledWidth = rect.width() * scaleX
                    val scaledHeight = rect.height() * scaleY
                    drawRect(
                        color = Color.Green,
                        topLeft = Offset(scaledLeft, scaledTop),
                        size = Size(scaledWidth, scaledHeight),
                        style = Stroke(width = 4f)
                    )
                    val doorCenterX = scaledLeft + (scaledWidth / 2)
                    val doorCenterY = scaledTop + (scaledHeight / 2)
                    drawCircle(
                        color = Color.Red,
                        radius = 10f,
                        center = Offset(doorCenterX, doorCenterY)
                    )
                    drawLine(
                        color = Color.Cyan,
                        start = Offset(size.width / 2, size.height),
                        end = Offset(doorCenterX, doorCenterY),
                        strokeWidth = 3f
                    )
                }
                obstacleDetections.forEach { detection ->
                    val rect = detection.boundingBox
                    val scaledLeft = rect.left * scaleX
                    val scaledTop = rect.top * scaleY
                    val scaledWidth = rect.width() * scaleX
                    val scaledHeight = rect.height() * scaleY
                    drawRect(
                        color = Color.Red,
                        topLeft = Offset(scaledLeft, scaledTop),
                        size = Size(scaledWidth, scaledHeight),
                        style = Stroke(width = 4f)
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = { capturePhoto(context, imageCapture, yoloTflite, yoloV8Tflite, onCapture) }) {
                    Text("Capture Photo")
                }
                Button(
                    onClick = {
                        isContinuousScanningEnabled = !isContinuousScanningEnabled
                        if (isContinuousScanningEnabled) {
                            tts.speak("Continuous guidance enabled", TextToSpeech.QUEUE_FLUSH, null, null)
                        } else {
                            tts.speak("Continuous guidance disabled", TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                    }
                ) {
                    Text(if (isContinuousScanningEnabled) "Stop Guidance" else "Start Guidance")
                }
            }
            Text("Guidance: $guidanceText", color = Color.White)
            if (doorDetections.isEmpty() && obstacleDetections.isEmpty()) {
                Text("No doors or obstacles detected", color = Color.White)
            } else {
                if (obstacleDetections.isNotEmpty()) {
                    val mainObstacle = obstacleDetections.maxByOrNull { it.confidence } ?: obstacleDetections[0]
                    Text("Obstacles: ${obstacleDetections.size} (Confidence: ${String.format("%.2f", mainObstacle.confidence)})", color = Color.Red)
                }
                if (doorDetections.isNotEmpty()) {
                    val mainDoor = doorDetections.maxByOrNull { it.confidence } ?: doorDetections[0]
                    Text("Doors: ${doorDetections.size} (Confidence: ${String.format("%.2f", mainDoor.confidence)})", color = Color.Green)
                }
            }
        }
        if (capturedBitmap != null) {
            LaunchedEffect(capturedBitmap) {
                withContext(Dispatchers.Default) {
                    val doors = processCapturedImage(capturedBitmap, yoloTflite, context)
                    val obstacles = processObstacleImage(capturedBitmap, yoloV8Tflite, context)
                    doorDetections = doors
                    obstacleDetections = obstacles
                    val newGuidance = generateNavigationGuidance(doors, obstacles, capturedBitmap!!.width, capturedBitmap!!.height)
                    if (newGuidance == previousDirection) {
                        directionConsistencyCount++
                        if (directionConsistencyCount >= 2 || guidanceText != newGuidance) {
                            guidanceText = newGuidance
                            tts.speak(guidanceText, TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                    } else {
                        previousDirection = newGuidance
                        directionConsistencyCount = 1
                        guidanceText = newGuidance
                        if (isSignificantlyDifferent(guidanceText, newGuidance)) {
                            tts.speak(guidanceText, TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                    }
                    lastGuidanceTime = System.currentTimeMillis()
                }
            }
        }
        if (isContinuousScanningEnabled) {
            LaunchedEffect(isContinuousScanningEnabled) {
                while (isContinuousScanningEnabled) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastGuidanceTime >= guidanceCooldown) {
                        capturePhoto(context, imageCapture, yoloTflite, yoloV8Tflite, onCapture)
                        lastGuidanceTime = currentTime
                    }
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
    }
}

private fun isSignificantlyDifferent(oldGuidance: String, newGuidance: String): Boolean {
    val significantTerms = listOf("left", "right", "straight", "forward", "stop", "turn", "obstacle", "door")
    for (term in significantTerms) {
        if ((oldGuidance.contains(term) && !newGuidance.contains(term)) ||
            (!oldGuidance.contains(term) && newGuidance.contains(term))) {
            return true
        }
    }
    if ((oldGuidance.contains("no door") && newGuidance.contains("door")) ||
        (!oldGuidance.contains("no door") && newGuidance.contains("no door"))) {
        return true
    }
    return false
}

@OptIn(ExperimentalGetImage::class)
fun processCapturedImage(bitmap: Bitmap, tflite: Interpreter, context: Context, rotationDegrees: Int = 0): List<DoorDetection> {
    val inputSize = 640
    val rotatedBitmap = if (rotationDegrees != 0) {
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } else {
        bitmap
    }
    val processedBitmap = Bitmap.createScaledBitmap(rotatedBitmap, inputSize, inputSize, true)
    val inputBuffer = preprocessImage(processedBitmap, inputSize)
    val output = Array(1) { Array(5) { FloatArray(8400) } }
    tflite.run(inputBuffer, output)
    val width = bitmap.width
    val height = bitmap.height
    Log.d("ProcessImage", "Using dimensions for scaling: width=$width, height=$height, rotation=$rotationDegrees, bitmap=${bitmap.width}x${bitmap.height}")
    val doorDetections = postProcessOutput(
        output,
        inputSize,
        width,
        height,
        rotationDegrees
    )
    doorDetections.forEachIndexed { index, door ->
        Log.d("DoorDetection", "Door #${index + 1}: confidence=${door.confidence}, " +
                "position=(${door.boundingBox.left}, ${door.boundingBox.top}, " +
                "${door.boundingBox.right}, ${door.boundingBox.bottom})")
    }
    if (rotatedBitmap != bitmap) {
        rotatedBitmap.recycle()
    }
    return doorDetections
}

@OptIn(ExperimentalGetImage::class)
fun processObstacleImage(
    bitmap: Bitmap,
    tflite: Interpreter,
    context: Context,
    rotationDegrees: Int = 0
): List<ObstacleDetection> {
    val inputSize = 640
    val numClasses = 80
    val confidenceThreshold = 0.5f
    val iouThreshold = 0.8f
    val rotatedBitmap = if (rotationDegrees != 0) {
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } else {
        bitmap
    }
    val processedBitmap = Bitmap.createScaledBitmap(rotatedBitmap, inputSize, inputSize, true)
    val inputBuffer = preprocessImage(processedBitmap, inputSize, isFloat32 = true)
    val output = Array(1) { Array(84) { FloatArray(8400) } }
    tflite.run(inputBuffer, output)
    val width = bitmap.width
    val height = bitmap.height
    val detections = mutableListOf<ObstacleDetection>()
    val labels = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train",
        "truck", "boat", "traffic light", "fire hydrant", "stop sign",
        "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep",
        "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella",
        "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
        "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon",
        "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot",
        "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant",
        "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote",
        "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
        "hair drier", "toothbrush"
    )
    for (i in 0 until 8400) {
        val x = output[0][0][i]
        val y = output[0][1][i]
        val w = output[0][2][i]
        val h = output[0][3][i]
        var maxConfidence = 0f
        var classId = -1
        for (j in 0 until numClasses) {
            val confidence = output[0][4 + j][i]
            if (confidence > maxConfidence) {
                maxConfidence = confidence
                classId = j
            }
        }
        if (maxConfidence > confidenceThreshold) {
            val scaledX = x * width / inputSize
            val scaledY = y * height / inputSize
            val scaledW = w * width / inputSize
            val scaledH = h * height / inputSize
            val left = (scaledX - scaledW / 2).coerceIn(0f, width.toFloat())
            val top = (scaledY - scaledH / 2).coerceIn(0f, height.toFloat())
            val right = (scaledX + scaledW / 2).coerceIn(0f, width.toFloat())
            val bottom = (scaledY + scaledH / 2).coerceIn(0f, height.toFloat())
            if (right > left && bottom > top) {
                detections.add(
                    ObstacleDetection(
                        label = labels.getOrNull(classId) ?: "Unknown",
                        confidence = maxConfidence,
                        boundingBox = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
                    )
                )
            }
        }
    }
    if (rotatedBitmap != bitmap) {
        rotatedBitmap.recycle()
    }
    processedBitmap.recycle()
    return applyObstacleNMS(detections, iouThreshold)
}

private fun preprocessImage(bitmap: Bitmap, inputSize: Int, isFloat32: Boolean = true): ByteBuffer {
    val bufferSize = if (isFloat32) inputSize * inputSize * 3 * 4 else inputSize * inputSize * 3 * 1
    val buffer = ByteBuffer.allocateDirect(bufferSize)
    buffer.order(ByteOrder.nativeOrder())
    buffer.rewind()
    val pixels = IntArray(inputSize * inputSize)
    bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
    if (isFloat32) {
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            buffer.putFloat((pixel and 0xFF) / 255.0f)
        }
    } else {
        for (pixel in pixels) {
            buffer.put(((pixel shr 16) and 0xFF).toByte())
            buffer.put(((pixel shr 8) and 0xFF).toByte())
            buffer.put((pixel and 0xFF).toByte())
        }
    }
    buffer.rewind()
    Log.d("PreprocessImage", "Buffer size: ${buffer.capacity()} bytes, isFloat32: $isFloat32")
    return buffer
}

private fun postProcessOutput(
    output: Array<Array<FloatArray>>,
    inputSize: Int,
    originalWidth: Int,
    originalHeight: Int,
    rotationDegrees: Int
): List<DoorDetection> {
    val detections = mutableListOf<DoorDetection>()
    val confidenceThreshold = 0.5f
    Log.d("PostProcess", "Original dimensions: width=$originalWidth, height=$originalHeight, rotation=$rotationDegrees")
    for (i in 0 until 8400) {
        val confidence = output[0][4][i]
        if (confidence > confidenceThreshold) {
            val x = output[0][0][i]
            val y = output[0][1][i]
            val w = output[0][2][i]
            val h = output[0][3][i]
            Log.d("PostProcess", "Raw output [$i]: x=$x, y=$y, w=$w, h=$h, confidence=$confidence")
            val scaledX = x * originalWidth
            val scaledY = y * originalHeight
            val scaledW = w * originalWidth
            val scaledH = h * originalHeight
            val rect = RectF(
                scaledX - scaledW / 2,
                scaledY - scaledH / 2,
                scaledX + scaledW / 2,
                scaledY + scaledH / 2
            )
            Log.d("PostProcess", "Scaled bounding box: left=${rect.left}, top=${rect.top}, right=${rect.right}, bottom=${rect.bottom}")
            val left = max(0f, rect.left)
            val top = max(0f, rect.top)
            val right = min(originalWidth.toFloat(), rect.right)
            val bottom = min(originalHeight.toFloat(), rect.bottom)
            Log.d("PostProcess", "Clamped bounding box: left=$left, top=$top, right=$right, bottom=$bottom")
            detections.add(
                DoorDetection(
                    label = "Door",
                    confidence = confidence,
                    boundingBox = Rect(
                        left.toInt(),
                        top.toInt(),
                        right.toInt(),
                        bottom.toInt()
                    )
                )
            )
        }
    }
    return applyNMS(detections)
}

private fun applyNMS(detections: List<DoorDetection>): List<DoorDetection> {
    val sortedDetections = detections.sortedByDescending { it.confidence }.toMutableList()
    val selectedDetections = mutableListOf<DoorDetection>()
    val iouThreshold = 0.8f
    while (sortedDetections.isNotEmpty()) {
        val first = sortedDetections.first()
        selectedDetections.add(first)
        sortedDetections.removeAt(0)
        sortedDetections.removeIf { next ->
            calculateIoU(first.boundingBox, next.boundingBox) >= iouThreshold
        }
    }
    Log.d("NMS", "Selected ${selectedDetections.size} detections after NMS")
    return selectedDetections
}

private fun applyObstacleNMS(detections: List<ObstacleDetection>, iouThreshold: Float = 0.8f): List<ObstacleDetection> {
    val sortedDetections = detections.sortedByDescending { it.confidence }.toMutableList()
    val selectedDetections = mutableListOf<ObstacleDetection>()
    while (sortedDetections.isNotEmpty()) {
        val first = sortedDetections.first()
        selectedDetections.add(first)
        sortedDetections.removeAt(0)
        sortedDetections.removeIf { next ->
            calculateIoU(first.boundingBox, next.boundingBox) >= iouThreshold
        }
    }
    Log.d("ObstacleNMS", "Selected ${selectedDetections.size} obstacle detections after NMS")
    return selectedDetections
}

private fun calculateIoU(box1: Rect, box2: Rect): Float {
    val x1 = max(box1.left, box2.left)
    val y1 = max(box1.top, box2.top)
    val x2 = min(box1.right, box2.right)
    val y2 = min(box1.bottom, box2.bottom)
    val intersectionArea = max(0, x2 - x1) * max(0, y2 - y1)
    val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
    val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
    return intersectionArea.toFloat() / (box1Area + box2Area - intersectionArea)
}

@OptIn(ExperimentalGetImage::class)
fun ImageProxy.toBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    Log.d("ImageProxy", "Bitmap dimensions: ${bitmap.width}x${bitmap.height}")
    return bitmap
}

data class DoorDetection(
    val label: String,
    val confidence: Float,
    val boundingBox: Rect
)

data class ObstacleDetection(
    val label: String,
    val confidence: Float,
    val boundingBox: Rect
)

private fun generateNavigationGuidance(
    doors: List<DoorDetection>,
    obstacles: List<ObstacleDetection>,
    imageWidth: Int,
    imageHeight: Int
): String {
    val confidentDoors = doors.filter { it.confidence >= 0.5f }
    val confidentObstacles = obstacles.filter { it.confidence >= 0.5f }
    if (confidentObstacles.isNotEmpty()) {
        Log.d("NavigationGuidance", "Obstacle detected: ${confidentObstacles.size} obstacles found")
        return "Stop! Obstacle detected in front of you."
    }
    if (confidentDoors.isEmpty()) {
        return "No doors detected. Turn around slowly to scan the room."
    }
    val door = confidentDoors.maxByOrNull { it.confidence } ?: return "No clear doors detected. Turn around slowly."
    val boundingBox = door.boundingBox
    val doorCenterX = (boundingBox.left + boundingBox.right) / 2f
    val normalizedDoorCenterX = doorCenterX / imageWidth
    val leftThreshold = 1f / 3f
    val rightThreshold = 2f / 3f
    Log.d(
        "NavigationGuidance",
        "doorCenterX=$normalizedDoorCenterX, leftThreshold=$leftThreshold, rightThreshold=$rightThreshold, " +
                "imageWidth=$imageWidth, imageHeight=$imageHeight, boundingBox=(${boundingBox.left}, ${boundingBox.top}, ${boundingBox.right}, ${boundingBox.bottom})"
    )
    return when {
        normalizedDoorCenterX < leftThreshold -> "Door detected on the left."
        normalizedDoorCenterX > rightThreshold -> "Door detected on the right."
        else -> "Door detected straight ahead."
    }
}

@OptIn(ExperimentalGetImage::class)
private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture?,
    yoloTflite: Interpreter,
    yoloV8Tflite: Interpreter,
    onCapture: (Bitmap) -> Unit
) {
    imageCapture?.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rotationDegrees = image.imageInfo.rotationDegrees
                    Log.d("CameraX", "Image captured with rotation: $rotationDegrees degrees")
                    val bitmap = image.toBitmap()
                    onCapture(bitmap)
                    val doors = processCapturedImage(bitmap, yoloTflite, context, rotationDegrees)
                    val obstacles = processObstacleImage(bitmap, yoloV8Tflite, context, rotationDegrees)
                    Log.d("Detection", "Processed ${doors.size} doors and ${obstacles.size} obstacles")
                } catch (e: Exception) {
                    Log.e("CameraX", "Failed to convert ImageProxy to Bitmap: ${e.message}", e)
                } finally {
                    image.close()
                }
            }
            override fun onError(exception: ImageCaptureException) {
                Log.e("CameraX", "Photo capture failed: ${exception.message}")
            }
        }) ?: Log.e("CameraX", "ImageCapture not initialized")
}