package com.audio.mobile

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.*
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.widget.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(60, 60, 60, 60)
        }

        val tvLabel = TextView(this).apply {
            text = "IP DEL TELEVISOR:"
            textSize = 18f
            setTextColor(Color.LTGRAY)
        }

        val etIp = EditText(this).apply {
            setText(prefs.getString("ip", "192.168.1."))
            textSize = 28f
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_PHONE
            gravity = Gravity.CENTER
        }

        val tvVol = TextView(this).apply {
            text = "Volumen Independiente TV: ${(ReceiverService.volume * 100).toInt()}%"
            textSize = 18f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 50, 0, 20)
        }

        val seekVol = SeekBar(this).apply {
            max = 100
            progress = (ReceiverService.volume * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                    ReceiverService.updateVolume(p / 100f)
                    tvVol.text = "Volumen Independiente TV: $p%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val btnConnect = Button(this).apply {
            text = if (ReceiverService.isRunning) "DESCONECTAR" else "CONECTAR AL TV"
            textSize = 22f
            setPadding(40, 30, 40, 30)
            setOnClickListener {
                if (ReceiverService.isRunning) {
                    stopService(Intent(this@MainActivity, ReceiverService::class.java))
                    text = "CONECTAR AL TV"
                } else {
                    val ip = etIp.text.toString().trim()
                    prefs.edit().putString("ip", ip).apply()
                    val intent = Intent(this@MainActivity, ReceiverService::class.java).apply {
                        putExtra("ip", ip)
                    }
                    startForegroundService(intent)
                    text = "DESCONECTAR"
                }
            }
        }

        val tvNote = TextView(this).apply {
            text = "Modo Llamada Activo: El audio del TV no se pausará al hablar por WhatsApp o Discord."
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 60, 0, 0)
        }

        layout.addView(tvLabel)
        layout.addView(etIp)
        layout.addView(tvVol)
        layout.addView(seekVol)
        layout.addView(btnConnect)
        layout.addView(tvNote)
        setContentView(layout)
    }
}

class ReceiverService : Service() {
    companion object {
        @Volatile var isRunning = false
        @Volatile var volume = 0.8f
        private var audioTrack: AudioTrack? = null

        fun updateVolume(v: Float) {
            volume = v
            try { audioTrack?.setVolume(v) } catch (e: Exception) {}
        }
    }

    private var socket: DatagramSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tvIp = intent?.getStringExtra("ip") ?: return START_NOT_STICKY

        val channelId = "mobile_rx_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channelId, "Receptor TV", NotificationManager.IMPORTANCE_LOW))
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Reproduciendo Audio del TV")
            .setContentText("Mezcla en llamada activa en segundo plano")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(2, notif)
        }

        // Evitar que el procesador o el chip Wi-Fi entren en ahorro de energía con pantalla apagada
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVAudio::CpuWakeLock").apply { acquire() }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TVAudio::WifiLock").apply { acquire() }

        startReceiving(tvIp)
        return START_STICKY
    }

    private fun startReceiving(tvIp: String) {
        isRunning = true
        socket = DatagramSocket(50006)

        // Hilo que envía un paquete de registro al TV cada 2 segundos para mantener vivo el enlace
        thread {
            val ping = "PING".toByteArray()
            while (isRunning) {
                try {
                    val addr = InetAddress.getByName(tvIp)
                    socket?.send(DatagramPacket(ping, ping.size, addr, 50005))
                    Thread.sleep(2000)
                } catch (e: Exception) {
                    try { Thread.sleep(2000) } catch (ex: Exception) {}
                }
            }
        }

        // Hilo de reproducción de audio sin AudioFocus (inmune a pausas por WhatsApp/Discord)
        thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val minBuf = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            audioTrack?.setVolume(volume)
            audioTrack?.play()

            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)

            while (isRunning) {
                try {
                    socket?.receive(pkt)
                    // WRITE_NON_BLOCKING evita congelamientos si algún paquete se retrasa en el Wi-Fi
                    audioTrack?.write(pkt.data, 0, pkt.length, AudioTrack.WRITE_NON_BLOCKING)
                } catch (e: Exception) { break }
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        try { socket?.close() } catch (e: Exception) {}
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
