package com.angel.readforme

import androidx.camera.core.ExperimentalGetImage
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SummaryActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textToSpeech: TextToSpeech

    private val GALLERY_REQUEST_CODE = 2001



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_summary)

        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)

        textToSpeech = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.getDefault()
            }
        }

        val galleryButton = findViewById<Button>(R.id.galleryButton)
        galleryButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, GALLERY_REQUEST_CODE)
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions.launch(REQUIRED_PERMISSIONS)
        }

        captureButton.setOnClickListener {
            takePhoto()
        }

        val backButton = findViewById<Button>(R.id.backButton)
        backButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }


        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    processImageProxy(imageProxy)
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@SummaryActivity, "Error al capturar imagen", Toast.LENGTH_SHORT).show()
                    Log.e("SummaryCamera", "Capture failed", exception)
                }
            }
        )
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val fullText = visionText.text
                    val summary = summarizeText(fullText)

                    // Mostrar en pantalla
                    val summaryTextView = findViewById<TextView>(R.id.summaryText)
                    summaryTextView.text = if (summary.isNotBlank()) {
                        summary
                    } else {
                        getString(R.string.no_summary_found)
                    }

                    // Leer en voz alta
                    speakText(summaryTextView.text.toString())
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Error al reconocer texto", Toast.LENGTH_SHORT).show()
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    // pequeño algoritmo con palabras clave para resumir de manera más efectiva
    private fun summarizeText(text: String): String {
        val keywords = listOf(
            "advertencia", "precaución", "uso", "efectos secundarios", "aclaraciones",
            "aclaración", "precauciones", "instrucción", "instruccion", "usos", "idea",
            "ideas", "menú", "menús", "carta", "total", "información general", "indicacción",
            "información", "informacion", "precaucion", "administracion", "prospecto",
            "causa", "causas", "contraindicaciones", "dosis", "instrucciones", "indicacion",
            "administración", "contenido", "riesgo", "alergia", "indicaciones", "riesgos"
        )

        val lines = text.lines()
            .map { it.trim() }
            .filter { line ->
                line.length in 40..160 &&
                        !line.contains("http", ignoreCase = true) &&
                        !line.matches(Regex("^\\d+\$")) &&
                        !line.lowercase().startsWith("tel") &&
                        !line.lowercase().contains("fax") &&
                        !line.lowercase().contains("email")
            }

        // Líneas con palabras clave
        val keywordLines = lines.filter { line ->
            keywords.any { keyword -> line.contains(keyword, ignoreCase = true) }
        }

        // Mayúsculas (y no son números)
        val uppercaseLines = lines.filter { line ->
            line == line.uppercase() && line.any { it.isLetter() }
        }

        // Resto de líneas
        val otherLines = lines - keywordLines - uppercaseLines

        // 4. Combinamos keyword con uppercase y resto
        val selectedLines = (keywordLines + uppercaseLines + otherLines)
            .distinct()
            .take(5) // máximo 5 para no abrumar al usuario

        return selectedLines.joinToString("\n\n")
    }



    private fun speakText(text: String) {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                Log.e("Camera", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                startCamera()
            } else {
                Toast.makeText(this, "Permisos no concedidos", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onDestroy() {
        textToSpeech.shutdown()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == GALLERY_REQUEST_CODE && resultCode == RESULT_OK && data != null && data.data != null) {
            val imageUri = data.data
            try {
                val inputImage = InputImage.fromFilePath(this, imageUri!!)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val summary = summarizeText(visionText.text)

                        val summaryTextView = findViewById<TextView>(R.id.summaryText)
                        summaryTextView.text = if (summary.isNotBlank()) {
                            summary
                        } else {
                            getString(R.string.no_summary_found)
                        }

                        speakText(summaryTextView.text.toString())
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Error al reconocer texto", Toast.LENGTH_SHORT).show()
                    }

            } catch (e: Exception) {
                Toast.makeText(this, "Error al cargar imagen", Toast.LENGTH_SHORT).show()
                Log.e("GalleryImport", "Error al procesar imagen de galería", e)
            }
        }
    }


    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
