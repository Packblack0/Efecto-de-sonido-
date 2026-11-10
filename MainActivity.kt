package com.packsound.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var info: EqInfo
    private lateinit var swEnable: SwitchMaterial
    private lateinit var tvStatus: TextView
    private lateinit var spPreset: Spinner
    private lateinit var eqContainer: LinearLayout
    private lateinit var sbBass: SeekBar
    private lateinit var sbVirt: SeekBar
    private lateinit var sbLoud: SeekBar
    private lateinit var tvBass: TextView
    private lateinit var tvVirt: TextView
    private lateinit var tvLoud: TextView

    private val bandBars = ArrayList<SeekBar>()
    private val bandLabels = ArrayList<TextView>()
    private var userPickedPreset = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1500)
        }
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        info = EqInfo.query(this)
        swEnable = findViewById(R.id.swEnable)
        tvStatus = findViewById(R.id.tvStatus)
        spPreset = findViewById(R.id.spPreset)
        eqContainer = findViewById(R.id.eqContainer)
        sbBass = findViewById(R.id.sbBass)
        sbVirt = findViewById(R.id.sbVirt)
        sbLoud = findViewById(R.id.sbLoud)
        tvBass = findViewById(R.id.tvBass)
        tvVirt = findViewById(R.id.tvVirt)
        tvLoud = findViewById(R.id.tvLoud)

        buildBands()
        loadSettingsIntoUi()
        setupEffectBars()
        setupPresets()
    }

    override fun onResume() {
        super.onResume()
        syncSwitch()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun syncSwitch() {
        swEnable.setOnCheckedChangeListener(null)
        swEnable.isChecked = PackSoundService.running
        swEnable.setOnCheckedChangeListener { _, on -> if (on) startSvc() else stopSvc() }
        updateStatus()
    }

    private fun startSvc() {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        ContextCompat.startForegroundService(this, Intent(this, PackSoundService::class.java))
    }

    private fun stopSvc() {
        stopService(Intent(this, PackSoundService::class.java))
    }

    private fun updateStatus() {
        tvStatus.text = if (PackSoundService.running)
            "Activo · apps con audio detectadas: ${PackSoundService.sessionCount}"
        else
            "Apagado"
    }

    private fun freqLabel(hz: Int) = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"

    private fun dbText(mb: Int): String {
        val db = mb / 100f
        return (if (db > 0) "+" else "") + String.format("%.1f dB", db)
    }

    private fun buildBands() {
        val range = info.maxLevel - info.minLevel
        info.freqs.forEachIndexed { i, hz ->
            val label = TextView(this)
            val bar = SeekBar(this).apply {
                max = range
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        label.text = "${freqLabel(hz)}   ${dbText(p + info.minLevel)}"
                        if (fromUser) saveAll()
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            label.text = freqLabel(hz)
            bandLabels.add(label)
            bandBars.add(bar)
            eqContainer.addView(label)
            eqContainer.addView(bar)
        }
    }

    private fun setupEffectBars() {
        sbBass.max = 1000
        sbVirt.max = 1000
        sbLoud.max = 1500
        bind(sbBass, tvBass, "Refuerzo de graves") { "${it / 10}%" }
        bind(sbVirt, tvVirt, "Sonido envolvente") { "${it / 10}%" }
        bind(sbLoud, tvLoud, "Volumen extra") { dbText(it) }
    }

    private fun bind(sb: SeekBar, tv: TextView, name: String, fmt: (Int) -> String) {
        tv.text = "$name   ${fmt(sb.progress)}"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                tv.text = "$name   ${fmt(p)}"
                if (fromUser) saveAll()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun loadSettingsIntoUi() {
        val s = Store.load(this)
        bandBars.forEachIndexed { i, bar ->
            bar.progress = (s.eq.getOrElse(i) { 0 } - info.minLevel).coerceIn(0, bar.max)
        }
        sbBass.progress = s.bass
        sbVirt.progress = s.virt
        sbLoud.progress = s.loud
    }

    @Suppress("ClickableViewAccessibility")
    private fun setupPresets() {
        spPreset.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Presets.all.map { it.name }
        )
        spPreset.setOnTouchListener { _, _ -> userPickedPreset = true; false }
        spPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!userPickedPreset) return
                userPickedPreset = false
                val preset = Presets.all[pos]
                val lv = Presets.levels(preset, info)
                bandBars.forEachIndexed { i, bar ->
                    bar.progress = (lv.getOrElse(i) { 0 } - info.minLevel).coerceIn(0, bar.max)
                }
                sbBass.progress = preset.bass
                sbVirt.progress = preset.virt
                sbLoud.progress = preset.loud
                saveAll()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun saveAll() {
        val e = Store.prefs(this).edit()
        bandBars.forEachIndexed { i, bar -> e.putInt("eq_$i", bar.progress + info.minLevel) }
        e.putInt("bass", sbBass.progress)
        e.putInt("virt", sbVirt.progress)
        e.putInt("loud", sbLoud.progress)
        e.apply()
    }
}
