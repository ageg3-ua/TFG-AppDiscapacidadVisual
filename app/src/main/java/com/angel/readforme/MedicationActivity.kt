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
import android.net.Uri
import android.view.View


class MedicationActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var backButton: Button
    private lateinit var resultText: TextView
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textToSpeech: TextToSpeech

    private lateinit var infoButton: Button
    private var lastDetectedMedicine: String = ""
    private lateinit var repeatButton: Button
    private var lastExtractedText: String = ""


    private val GALLERY_REQUEST_CODE = 3001

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

        val galleryButton = findViewById<Button>(R.id.galleryButton)
        galleryButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, GALLERY_REQUEST_CODE)
        }

        infoButton = findViewById(R.id.infoButton)
        infoButton.setOnClickListener {
            if (lastDetectedMedicine.isNotBlank() && lastDetectedMedicine != "Nombre no detectado") {
                val query = Uri.encode(lastDetectedMedicine)
                val url = "https://www.google.com/search?q=$query medicamento"
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(browserIntent)
                speakText("Abriendo información sobre $lastDetectedMedicine")
            } else {
                Toast.makeText(this, "No se ha detectado ningún medicamento", Toast.LENGTH_SHORT).show()
                speakText("No se ha detectado ningún medicamento")
            }
        }

        repeatButton = findViewById(R.id.repeatButton)
        repeatButton.setOnClickListener {
            if (lastExtractedText.isNotBlank()) {
                detectLanguageAndSpeak(lastExtractedText)
            }
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
                    detectLanguageAndSpeak(extractedInfo)
                    lastExtractedText = extractedInfo
                    repeatButton.visibility = View.VISIBLE

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

        val name = lines.map { it.uppercase() }.find { line ->
            knownMedicines.any { med -> line.contains(med) }
        } ?: "Nombre no detectado"

        val doseRegex = Regex("\\b\\d{1,4}\\s?(mg|ml|g)\\b", RegexOption.IGNORE_CASE)
        val dose = lines.find { doseRegex.containsMatchIn(it) } ?: "Dosis no detectada"

        val expiryRegex = Regex("\\b(\\d{2}[/-]\\d{2,4}|\\d{2,4}[/-]\\d{2})\\b")
        val expiryRaw = lines.find {
            expiryRegex.containsMatchIn(it) ||
                    it.lowercase().contains("cad") || it.lowercase().contains("exp")
        } ?: "Caducidad no detectada"

        // Verificación caducidad
        val warning: String = try {
            val numbers = Regex("\\d+").findAll(expiryRaw).map { it.value }.toList()
            val formattedDate = when {
                numbers.size == 2 -> "${numbers[1]}-${numbers[0]}-01"
                numbers.size == 3 -> "${numbers[2]}-${numbers[1]}-${numbers[0]}"
                else -> null
            }
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val expiryDate = sdf.parse(formattedDate ?: "")
            if (expiryDate != null && expiryDate.before(Date())) {
                runOnUiThread {
                    resultText.setTextColor(android.graphics.Color.RED)
                    Toast.makeText(this, "⚠️ El medicamento está caducado", Toast.LENGTH_LONG).show()
                    speakText("¡Atención! Este medicamento está caducado.")
                }
                "⚠️ Medicamento CADUCADO"
            } else ""
        } catch (e: Exception) {
            ""
        }

        runOnUiThread {
            resultText.setTextColor(android.graphics.Color.BLACK)
        }

        return "💊 Medicamento: $name\n📏 Dosis: $dose\n📅 Caducidad: $expiryRaw\n$warning"
    }



    private fun speakText(text: String) {
        val textWithoutEmojis = text.replace(Regex("[\\p{So}\\p{Cn}]+"), "")
        textToSpeech.speak(textWithoutEmojis, TextToSpeech.QUEUE_FLUSH, null, null)
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

    private fun detectLanguageAndSpeak(text: String) {
        val languageIdentifier = com.google.mlkit.nl.languageid.LanguageIdentification.getClient()

        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode ->
                if (languageCode == "es") {
                    textToSpeech.language = Locale("es", "ES")
                } else if (languageCode == "en") {
                    textToSpeech.language = Locale.UK
                } else {
                    textToSpeech.language = Locale.getDefault()
                }

                val cleanText = text.replace(Regex("[\\p{So}\\p{Cn}]+"), "")
                textToSpeech.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, null)
            }
            .addOnFailureListener {
                // Si falla, usar idioma por defecto
                val cleanText = text.replace(Regex("[\\p{So}\\p{Cn}]+"), "")
                textToSpeech.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, null)
            }
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
                        val extractedInfo = extractMedicationInfo(visionText.text)
                        resultText.text = extractedInfo
                        detectLanguageAndSpeak(extractedInfo)
                        lastExtractedText = extractedInfo
                        repeatButton.visibility = View.VISIBLE

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
