package com.packsound.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Servicio en primer plano. Escucha cuando otras apps de audio abren/cierran su sesión
 * de audio y les aplica la cadena de efectos. También intenta una sesión global (0),
 * que solo funciona en algunos dispositivos.
 */
class PackSoundService : Service() {

    companion object {
        const val ACTION_STOP = "com.packsound.app.STOP"
        private const val CHANNEL_ID = "packsound"
        private const val NOTIF_ID = 1

        @Volatile var running = false
        @Volatile var sessionCount = 0
    }

    private val chains = HashMap<Int, EffectChain>()
    private var globalChain: EffectChain? = null
    private val handler = Handler(Looper.getMainLooper())
    private var settings = Settings(emptyList(), 0, 0, 0)

    private val reapply = Runnable {
        settings = Store.load(this)
        globalChain?.apply(settings)
        chains.values.forEach { it.apply(settings) }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.removeCallbacks(reapply)
        handler.postDelayed(reapply, 60)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
            if (session <= 0) return
            if (intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) == packageName) return
            chains.remove(session)?.release()
            if (intent.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
                val chain = EffectChain(session)
                if (chain.ok) {
                    chain.apply(settings)
                    chains[session] = chain
                }
            }
            sessionCount = chains.size
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Store.load(this)

        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)

        // Intento de efecto global (puede no funcionar en Android moderno)
        val g = EffectChain(0)
        if (g.ok) {
            g.apply(settings)
            globalChain = g
        }
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PackSound", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PackSoundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("PackSound activo")
            .setContentText("Mejorando el audio de tus apps")
            .setContentIntent(open)
            .addAction(0, "Detener", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        running = false
        sessionCount = 0
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(receiver) } catch (_: Throwable) {}
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        chains.values.forEach { it.release() }
        chains.clear()
        globalChain?.release()
        globalChain = null
        super.onDestroy()
    }
}
