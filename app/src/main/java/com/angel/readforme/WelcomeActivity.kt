package com.angel.readforme
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import android.widget.ArrayAdapter
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.TextView

class WelcomeActivity : AppCompatActivity() {

    private lateinit var startButton: Button
    private lateinit var languageSpinner: Spinner
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("WelcomeActivity", "onCreate called")
        val prefs = getSharedPreferences("ReadForMePrefs", Context.MODE_PRIVATE)
        val lang = prefs.getString("lang", "es") ?: "es"
        setAppLocale(lang)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        startButton = findViewById(R.id.startButton)
        languageSpinner = findViewById(R.id.languageSpinner)

        // Listado de idiomas y banderas
        val languages = listOf("Español", "English")
        val flags = listOf(R.drawable.spain, R.drawable.unkin) // Aquí se deben colocar las banderas correspondientes

        // Adaptador para el Spinner
        languageSpinner.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, languages) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = layoutInflater.inflate(R.layout.language_item, parent, false)
                val img = view.findViewById<ImageView>(R.id.flag)
                val txt = view.findViewById<TextView>(R.id.langLabel)
                img.setImageResource(flags[position])  // Establecer bandera
                txt.text = languages[position]  // Establecer nombre del idioma
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return getView(position, convertView, parent)
            }
        }

        // Seleccionar idioma guardado
        languageSpinner.setSelection(if ((prefs.getString("lang", "es") ?: "es") == "en") 1 else 0)

        // CAMBIO DE IDIOMA EN CUANTO SE SELECCIONA
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedLang = if (position == 1) "en" else "es"
                val currentLang = prefs.getString("lang", "es")
                if (selectedLang != currentLang) {
                    prefs.edit().putString("lang", selectedLang).apply()
                    setAppLocale(selectedLang)
                    recreate()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        val aboutButton = findViewById<Button>(R.id.aboutButton)
        aboutButton.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }


        // Acción al hacer clic en el botón de inicio
        startButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }

    }

    private fun setAppLocale(language: String) {
        val locale = Locale(language)
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

}