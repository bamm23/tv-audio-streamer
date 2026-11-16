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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 40, 50, 40)
        }

        val tvLabel = TextView(this).apply {
            text = "IP DEL TELEVISOR:"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        val etIp = EditText(this).apply {
            setText(prefs.getString("ip", "192.168.1."))
            textSize = 24f
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_PHONE
            gravity = Gravity.CENTER
        }

        val tvVolTv = TextView(this).apply {
            text = "1. Volumen de la Tele: ${(ReceiverService.tvVolume * 100).toInt()}%"
            textSize = 17f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 30, 0, 10)
        }

        val seekVolTv = SeekBar(this).apply {
            max = 200
            progress = (ReceiverService.tvVolume * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                    ReceiverService.updateTvVolume(p / 100f)
                    val label = if (p > 100) "$p% (Amplificado)" else "$p%"
                    tvVolTv.text = "1. Volumen de la Tele: $label"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val maxCallVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val curCallVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)

        val tvVolCall = TextView(this).apply {
            text = "2. Volumen Llamada (WhatsApp/Discord): ${(curCallVol * 100) / maxOf(1, maxCallVol)}%"
            textSize = 17f
            setTextColor(Color.parseColor("#29B6F6"))
            setPadding(0, 25, 0, 10)
        }

        val seekVolCall = SeekBar(this).apply {
            max = maxCallVol
            progress = curCallVol
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, p, 0)
                    val pct = (p * 100) / maxOf(1, maxCallVol)
                    tvVolCall.text = "2. Volumen Llamada (WhatsApp/Discord): $pct%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        // --- INTERRUPTOR PARA FORZAR ALTA CALIDAD (A2DP) EN LLAMADAS ---
        val chkForceHd = CheckBox(this).apply {
            text = "Mantener Calidad HD en llamada (Usa el micrófono del celular para no bajar la calidad de los audífonos)"
            setTextColor(Color.WHITE)
            textSize = 14f
            isChecked = ReceiverService.forceHdAudio
            setPadding(10, 25, 10, 15)
            setOnCheckedChangeListener { _, isChecked ->
                ReceiverService.forceHdAudio = isChecked
                if (isChecked) {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                }
            }
        }

        val tvSyncLabel = TextView(this).apply {
            text = getSyncText(ReceiverService.syncOffsetMs)
            textSize = 16f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 15)
        }

        val syncRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnMinus = Button(this).apply {
            text = " - Adelantar "
            textSize = 15f
            setOnClickListener {
                ReceiverService.syncOffsetMs = maxOf(-100, ReceiverService.syncOffsetMs - 10)
                ReceiverService.triggerFlush = true
                tvSyncLabel.text = getSyncText(ReceiverService.syncOffsetMs)
            }
        }

        val btnZero = Button(this).apply {
            text = " Reset (0 ms) "
            textSize = 14f
            setOnClickListener {
                ReceiverService.syncOffsetMs = 0
                ReceiverService.triggerFlush = true
                tvSyncLabel.text = getSyncText(0)
            }
        }

        val btnPlus = Button(this).apply {
            text = " + Retrasar "
            textSize = 15f
            setOnClickListener {
                ReceiverService.syncOffsetMs = minOf(500, ReceiverService.syncOffsetMs + 10)
                tvSyncLabel.text = getSyncText(ReceiverService.syncOffsetMs)
            }
        }

        syncRow.addView(btnMinus)
        syncRow.addView(btnZero)
        syncRow.addView(btnPlus)

        val btnConnect = Button(this).apply {
            text = if (ReceiverService.isRunning) "DESCONECTAR" else "CONECTAR AL TV"
            textSize = 20f
            setPadding(40, 25, 40, 25)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 35 }
            layoutParams = params

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

        layout.addView(tvLabel)
        layout.addView(etIp)
        layout.addView(tvVolTv)
        layout.addView(seekVolTv)
        layout.addView(tvVolCall)
        layout.addView(seekVolCall)
        layout.addView(chkForceHd)
        layout.addView(tvSyncLabel)
        layout.addView(syncRow)
        layout.addView(btnConnect)

        scroll.addView(layout)
        setContentView(scroll)
    }

    private fun getSyncText(ms: Int): String {
        return when {
            ms < 0 -> "Sincronización: $ms ms (Modo Anti-Delay)"
            ms > 0 -> "Sincronización: +$ms ms (Audio retrasado)"
            else -> "Sincronización: 0 ms (Tiempo real)"
        }
    }
}

class ReceiverService : Service() {
    companion object {
        @Volatile var isRunning = false
        @Volatile var tvVolume = 1.0f
        @Volatile var syncOffsetMs = 0
        @Volatile var triggerFlush = false
        @Volatile var forceHdAudio = true
        private var audioTrack: AudioTrack? = null

        fun updateTvVolume(v: Float) {
            tvVolume = v
            try { audioTrack?.setVolume(minOf(1.0f, v)) } catch (e: Exception) {}
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
            .setContentTitle("Audio de TV + Llamada HD Activo")
            .setContentText("Sonido estéreo 48kHz protegido")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(2, notif)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVAudio::CpuWakeLock").apply { acquire() }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TVAudio::WifiLock").apply { acquire() }

        startReceiving(tvIp)
        return START_STICKY
    }

    private fun startReceiving(tvIp: String) {
        isRunning = true
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        socket = DatagramSocket(50006).apply {
            receiveBufferSize = 3840
        }

        // Hilo que envía latidos al TV y vigila que Discord no degrade la calidad Bluetooth a SCO (8kHz)
        thread {
            val ping = "PING".toByteArray()
            while (isRunning) {
                try {
                    if (forceHdAudio && audioManager.isBluetoothScoOn) {
                        audioManager.isBluetoothScoOn = false
                        audioManager.stopBluetoothSco()
                    }
                    val addr = InetAddress.getByName(tvIp)
                    socket?.send(DatagramPacket(ping, ping.size, addr, 50005))
                    Thread.sleep(1500)
                } catch (e: Exception) {
                    try { Thread.sleep(1500) } catch (ex: Exception) {}
                }
            }
        }

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

            audioTrack?.setVolume(minOf(1.0f, tvVolume))
            audioTrack?.play()

            val delayQueue = ConcurrentLinkedQueue<ByteArray>()
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            var packetCounter = 0

            while (isRunning) {
                try {
                    socket?.receive(pkt)
                    packetCounter++

                    if (triggerFlush) {
                        delayQueue.clear()
                        audioTrack?.pause()
                        audioTrack?.flush()
                        audioTrack?.play()
                        triggerFlush = false
                    }

                    val dataToPlay = pkt.data.copyOfRange(0, pkt.length)

                    if (tvVolume > 1.0f) {
                        applyAudioBoost(dataToPlay, tvVolume)
                    }

                    val currentOffset = syncOffsetMs

                    if (currentOffset > 0) {
                        val targetQueueSize = currentOffset / 10
                        delayQueue.add(dataToPlay)
                        if (delayQueue.size > targetQueueSize) {
                            val delayedPkt = delayQueue.poll()
                            if (delayedPkt != null) {
                                audioTrack?.write(delayedPkt, 0, delayedPkt.size, AudioTrack.WRITE_NON_BLOCKING)
                            }
                        }
                    } else if (currentOffset < 0) {
                        val skipFactor = maxOf(5, 25 + (currentOffset / 5))
                        if (packetCounter % skipFactor != 0) {
                            audioTrack?.write(dataToPlay, 0, dataToPlay.size, AudioTrack.WRITE_NON_BLOCKING)
                        }
                    } else {
                        delayQueue.clear()
                        audioTrack?.write(dataToPlay, 0, dataToPlay.size, AudioTrack.WRITE_NON_BLOCKING)
                    }
                } catch (e: Exception) { break }
            }
        }
    }

    private fun applyAudioBoost(pcmBytes: ByteArray, boostFactor: Float) {
        val shortBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (i in 0 until shortBuf.limit()) {
            val sample = (shortBuf.get(i) * boostFactor).toInt()
            val clamped = when {
                sample > Short.MAX_VALUE -> Short.MAX_VALUE.toInt()
                sample < Short.MIN_VALUE -> Short.MIN_VALUE.toInt()
                else -> sample
            }
            shortBuf.put(i, clamped.toShort())
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
