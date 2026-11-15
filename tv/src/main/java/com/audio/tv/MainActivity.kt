package com.audio.tv

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var btnToggle: Button
    private lateinit var projMgr: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(60, 60, 60, 60)
        }

        val tvTitle = TextView(this).apply {
            text = "IP DEL TELEVISOR (Ingresa esto en tu celular):"
            textSize = 20f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        val tvIp = TextView(this).apply {
            text = getLocalIp()
            textSize = 48f
            setTextColor(Color.parseColor("#00E676"))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 50)
        }

        btnToggle = Button(this).apply {
            text = if (CaptureService.isRunning) "DETENER TRANSMISIÓN" else "INICIAR TRANSMISIÓN"
            textSize = 24f
            setPadding(50, 30, 50, 30)
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            setOnClickListener {
                if (CaptureService.isRunning) {
                    stopService(Intent(this@MainActivity, CaptureService::class.java))
                    text = "INICIAR TRANSMISIÓN"
                } else {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                    } else {
                        startActivityForResult(projMgr.createScreenCaptureIntent(), 100)
                    }
                }
            }
        }

        layout.addView(tvTitle)
        layout.addView(tvIp)
        layout.addView(btnToggle)
        setContentView(layout)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startActivityForResult(projMgr.createScreenCaptureIntent(), 100)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, CaptureService::class.java).apply {
                putExtra("code", resultCode)
                putExtra("data", data)
            }
            startForegroundService(intent)
            btnToggle.text = "DETENER TRANSMISIÓN"
        }
    }

    private fun getLocalIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (ex: Exception) { }
        return "Verifica tu Wi-Fi"
    }
}

class CaptureService : Service() {
    companion object {
        @Volatile var isRunning = false
        @Volatile var clientIp: InetAddress? = null
    }

    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var socket: DatagramSocket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "tv_audio_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channelId, "TV Audio", NotificationManager.IMPORTANCE_LOW))
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Transmitiendo Audio del TV")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }

        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)

        startStreaming()
        return START_NOT_STICKY
    }

    private fun startStreaming() {
        isRunning = true
        socket = DatagramSocket(50005)

        // Hilo que escucha cuando el celular se conecta o envía latidos (keep-alive)
        thread {
            val buf = ByteArray(16)
            val pkt = DatagramPacket(buf, buf.size)
            while (isRunning) {
                try {
                    socket?.receive(pkt)
                    clientIp = pkt.address
                } catch (e: Exception) { break }
            }
        }

        // Hilo de captura de baja latencia y envío por UDP
        thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val packetSize = 1920 // 10 milisegundos exactos de audio estéreo a 48kHz
            val minBuf = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)

            recorder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, packetSize * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build()

            recorder?.startRecording()
            val audioBuf = ByteArray(packetSize)

            while (isRunning) {
                val read = recorder?.read(audioBuf, 0, audioBuf.size) ?: 0
                val target = clientIp
                if (read > 0 && target != null) {
                    try {
                        socket?.send(DatagramPacket(audioBuf, read, target, 50006))
                    } catch (e: Exception) { }
                }
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        try { socket?.close() } catch (e: Exception) {}
        try { recorder?.stop(); recorder?.release() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
