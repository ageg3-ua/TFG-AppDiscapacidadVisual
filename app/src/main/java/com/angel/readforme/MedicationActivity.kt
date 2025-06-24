package com.angel.readforme

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

class MedicationActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var backButton: Button
    private lateinit var resultText: TextView
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textToSpeech: TextToSpeech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_medication)

        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        backButton = findViewById(R.id.backButton)
        resultText = findViewById(R.id.medicationText)

        textToSpeech = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.getDefault()
            }
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions.launch(REQUIRED_PERMISSIONS)
        }

        captureButton.setOnClickListener {
            takePhoto()
        }

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
                    Toast.makeText(this@MedicationActivity, "Error al capturar imagen", Toast.LENGTH_SHORT).show()
                    Log.e("MedicationCamera", "Capture failed", exception)
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
                    val extractedInfo = extractMedicationInfo(fullText)
                    resultText.text = extractedInfo
                    speakText(extractedInfo)
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

    private fun extractMedicationInfo(text: String): String {
        val lines = text.lines().map { it.trim() }

        val knownMedicines = listOf(
            "PARACETAMOL", "IBUPROFENO", "AMOXICILINA", "ENANTYUM", "OMEPRAZOL",
            "AUGMENTINE", "DOLIPRANE", "ALMAX", "NAPROXENO", "NORFLOXACINO",
            "ESPIDIFEN", "BUSCAPINA", "SIBUTRAMINA", "LOSARTAN", "SIMVASTATINA"
        )

        // Buscar el nombre del medicamento usando la lista blanca (en mayúsculas)
        val name = lines.map { it.uppercase() }.find { line ->
            knownMedicines.any { med -> line.contains(med) }
        } ?: "Nombre no detectado"

        // Buscar dosis con expresiones flexibles: 200mg, 5 ml, 1000 g, etc.
        val doseRegex = Regex("\\b\\d{1,4}\\s?(mg|ml|g)\\b", RegexOption.IGNORE_CASE)
        val dose = lines.find { doseRegex.containsMatchIn(it) } ?: "Dosis no detectada"

        // Buscar caducidad con diferentes formatos
        val expiryRegex = Regex("\\b(\\d{2}[/-]\\d{2,4}|\\d{2,4}[/-]\\d{2})\\b")
        val expiry = lines.find {
            expiryRegex.containsMatchIn(it) ||
                    it.lowercase().contains("cad") || it.lowercase().contains("exp")
        } ?: "Caducidad no detectada"

        return "💊 Medicamento: $name\n📏 Dosis: $dose\n📅 Caducidad: $expiry"
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

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
