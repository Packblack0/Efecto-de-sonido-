package com.packsound.app

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log

const val TAG = "PackSound"
const val MAX_BANDS = 12

/** eq: nivel por banda en milibelios. bass/virt: 0..1000. loud: ganancia en milibelios (0..1500). */
data class Settings(val eq: List<Int>, val bass: Int, val virt: Int, val loud: Int)

object Store {
    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("packsound", Context.MODE_PRIVATE)

    fun load(c: Context): Settings {
        val p = prefs(c)
        return Settings(
            List(MAX_BANDS) { p.getInt("eq_$it", 0) },
            p.getInt("bass", 0),
            p.getInt("virt", 0),
            p.getInt("loud", 0)
        )
    }
}

/** Información del ecualizador del dispositivo (bandas, frecuencias y rango). */
class EqInfo(val minLevel: Int, val maxLevel: Int, val freqs: List<Int>) {
    companion object {
        fun query(ctx: Context): EqInfo {
            return try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val e = Equalizer(0, am.generateAudioSessionId())
                val r = e.bandLevelRange
                val f = (0 until e.numberOfBands.toInt()).map { e.getCenterFreq(it.toShort()) / 1000 }
                e.release()
                EqInfo(r[0].toInt(), r[1].toInt(), f)
            } catch (t: Throwable) {
                Log.w(TAG, "Usando valores por defecto del ecualizador: ${t.message}")
                EqInfo(-1500, 1500, listOf(60, 230, 910, 3600, 14000))
            }
        }
    }
}

data class Preset(val name: String, val curve: List<Double>, val bass: Int, val virt: Int, val loud: Int)

object Presets {
    val all = listOf(
        Preset("Normal", listOf(0.0, 0.0, 0.0, 0.0, 0.0), 0, 0, 0),
        Preset("Graves potentes", listOf(0.8, 0.5, 0.0, 0.0, 0.1), 700, 200, 300),
        Preset("Música", listOf(0.4, 0.2, -0.1, 0.3, 0.5), 300, 300, 300),
        Preset("Película", listOf(0.5, 0.2, 0.1, 0.3, 0.4), 400, 800, 400),
        Preset("Juegos", listOf(0.3, 0.0, 0.2, 0.5, 0.3), 200, 600, 300),
        Preset("Voz", listOf(-0.4, -0.1, 0.4, 0.5, 0.1), 0, 0, 300),
        Preset("Agudos claros", listOf(-0.2, -0.1, 0.1, 0.5, 0.8), 0, 100, 200)
    )

    /** Convierte la curva de 5 puntos al número real de bandas del dispositivo. */
    fun levels(p: Preset, info: EqInfo): List<Int> {
        val n = info.freqs.size
        return List(n) { i ->
            val pos = if (n == 1) 0.0 else i * (p.curve.size - 1).toDouble() / (n - 1)
            val lo = pos.toInt().coerceAtMost(p.curve.size - 1)
            val hi = (lo + 1).coerceAtMost(p.curve.size - 1)
            val v = p.curve[lo] + (p.curve[hi] - p.curve[lo]) * (pos - lo)
            if (v >= 0) (v * info.maxLevel).toInt() else (v * -info.minLevel).toInt()
        }
    }
}

/** Cadena de efectos (EQ + graves + envolvente + volumen) sobre una sesión de audio. */
class EffectChain(private val session: Int) {
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null

    init {
        eq = create("Equalizer") { Equalizer(100, session) }
        bass = create("BassBoost") { BassBoost(100, session) }
        virt = create("Virtualizer") { Virtualizer(100, session) }
        loud = create("Loudness") { LoudnessEnhancer(session) }
    }

    val ok: Boolean get() = eq != null || bass != null || virt != null || loud != null

    private fun <T> create(name: String, f: () -> T): T? = try {
        f()
    } catch (t: Throwable) {
        Log.w(TAG, "$name no disponible en sesión $session: ${t.message}")
        null
    }

    fun apply(s: Settings) {
        try {
            eq?.let { e ->
                val r = e.bandLevelRange
                for (i in 0 until e.numberOfBands.toInt()) {
                    val v = s.eq.getOrElse(i) { 0 }.coerceIn(r[0].toInt(), r[1].toInt())
                    e.setBandLevel(i.toShort(), v.toShort())
                }
                e.setEnabled(true)
            }
        } catch (t: Throwable) { Log.w(TAG, "EQ: ${t.message}") }
        try {
            bass?.let {
                if (it.strengthSupported) it.setStrength(s.bass.coerceIn(0, 1000).toShort())
                it.setEnabled(s.bass > 0)
            }
        } catch (t: Throwable) { Log.w(TAG, "Bass: ${t.message}") }
        try {
            virt?.let {
                if (it.strengthSupported) it.setStrength(s.virt.coerceIn(0, 1000).toShort())
                it.setEnabled(s.virt > 0)
            }
        } catch (t: Throwable) { Log.w(TAG, "Virt: ${t.message}") }
        try {
            loud?.let {
                it.setTargetGain(s.loud.coerceIn(0, 1500))
                it.setEnabled(s.loud > 0)
            }
        } catch (t: Throwable) { Log.w(TAG, "Loud: ${t.message}") }
    }

    fun release() {
        try { eq?.release() } catch (_: Throwable) {}
        try { bass?.release() } catch (_: Throwable) {}
        try { virt?.release() } catch (_: Throwable) {}
        try { loud?.release() } catch (_: Throwable) {}
    }
}
