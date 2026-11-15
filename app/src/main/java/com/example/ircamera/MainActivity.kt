package com.example.ircamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.sqrt
import android.content.ContentValues

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var irStatus: TextView
    private lateinit var irHint: TextView
    private lateinit var btnToggleIr: MaterialButton
    private lateinit var btnTorch: MaterialButton
    private lateinit var btnNight: MaterialButton
    private lateinit var btnCapture: MaterialButton
    private lateinit var irContainer: View
    private lateinit var wifiContainer: View
    private lateinit var portContainer: View
    private lateinit var audioContainer: View
    private lateinit var magnetContainer: View
    private lateinit var tabLayout: TabLayout
    private lateinit var bottomBar: View
    private lateinit var nightFilter: View
    private lateinit var sliderSensitivity: Slider
    private lateinit var btnToggleSlider: ImageView
    private lateinit var sliderLayout: LinearLayout

    private lateinit var wifiStatus: TextView
    private lateinit var wifiList: TextView
    private lateinit var btnScanWifi: MaterialButton
    private lateinit var wifiScanner: WifiScanner

    private lateinit var etGateway: EditText
    private lateinit var btnScanPort: MaterialButton
    private lateinit var portStatus: TextView

    private lateinit var audioCircle: CircularProgressIndicator
    private lateinit var audioValue: TextView
    private lateinit var magnetCircle: CircularProgressIndicator
    private lateinit var magnetValue: TextView

    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var analyzer: IRSpotAnalyzer? = null
    private var isIrRunning = false
    private var isTorchOn = false
    private var isNightMode = false
    private var isSliderExpanded = true

    private var irDetectionCount = 0
    private var lastWifiResults = "Не сканировалось"
    private var lastPortResult = "Не проверялось"
    private var lastMagnetValue = "0 мкТл"
    private var lastAudioValue = "0 dB"

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 44100
    private var audioThread: Thread? = null

    // Для автосохранения истории
    private var lastSaveTime = 0L
    private val saveIntervalMs = 5000L

    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val cameraOk = grants[Manifest.permission.CAMERA] == true
            val locationOk = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val audioOk = grants[Manifest.permission.RECORD_AUDIO] == true

            if (cameraOk) startCamera()
            else irStatus.text = "Нет доступа к камере"
            if (locationOk) wifiStatus.text = "Разрешения получены"
            if (audioOk) startAudioDetector()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            Log.e("CRASH", "Приложение упало", e)
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Ошибка: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }

        setContentView(R.layout.activity_main)

        val btnHelp = findViewById<ImageView>(R.id.btnHelp)
        val btnHistory = findViewById<ImageView>(R.id.btnHistory)
        val btnReset = findViewById<MaterialButton>(R.id.btnReset)
        btnToggleSlider = findViewById(R.id.btnToggleSlider)
        sliderLayout = findViewById(R.id.sliderLayout)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        irStatus = findViewById(R.id.irStatus)
        irHint = findViewById(R.id.irHint)
        btnToggleIr = findViewById(R.id.btnToggleIr)
        btnTorch = findViewById(R.id.btnTorch)
        btnNight = findViewById(R.id.btnNight)
        btnCapture = findViewById(R.id.btnCapture)
        irContainer = findViewById(R.id.irContainer)
        wifiContainer = findViewById(R.id.wifiContainer)
        portContainer = findViewById(R.id.portContainer)
        audioContainer = findViewById(R.id.audioContainer)
        magnetContainer = findViewById(R.id.magnetContainer)
        tabLayout = findViewById(R.id.tabLayout)
        bottomBar = findViewById(R.id.bottomBar)
        nightFilter = findViewById(R.id.nightFilter)
        sliderSensitivity = findViewById(R.id.sliderSensitivity)

        wifiStatus = findViewById(R.id.wifiStatus)
        wifiList = findViewById(R.id.wifiList)
        btnScanWifi = findViewById(R.id.btnScanWifi)
        wifiScanner = WifiScanner(this) { lines ->
            runOnUiThread {
                wifiList.text = lines.joinToString("\n\n")
                lastWifiResults = lines.joinToString("\n")
                autoSaveToHistory("Wi-Fi сканирование")
            }
        }

        etGateway = findViewById(R.id.etGateway)
        btnScanPort = findViewById(R.id.btnScanPort)
        portStatus = findViewById(R.id.portStatus)

        audioCircle = findViewById(R.id.audioCircle)
        audioValue = findViewById(R.id.audioValue)
        magnetCircle = findViewById(R.id.magnetCircle)
        magnetValue = findViewById(R.id.magnetValue)

        cameraExecutor = Executors.newSingleThreadExecutor()
        btnToggleIr.isEnabled = false

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        sliderSensitivity.addOnChangeListener { _, value, _ ->
            analyzer?.updateSensitivity(value.toInt())
        }

        btnToggleSlider.setOnClickListener {
            isSliderExpanded = !isSliderExpanded
            if (isSliderExpanded) {
                sliderLayout.visibility = View.VISIBLE
                btnToggleSlider.setImageResource(android.R.drawable.arrow_up_float)
            } else {
                sliderLayout.visibility = View.GONE
                btnToggleSlider.setImageResource(android.R.drawable.arrow_down_float)
            }
        }

        tabLayout.addTab(tabLayout.newTab().setText("ИК"))
        tabLayout.addTab(tabLayout.newTab().setText("Wi-Fi"))
        tabLayout.addTab(tabLayout.newTab().setText("Порт"))
        tabLayout.addTab(tabLayout.newTab().setText("Звук"))
        tabLayout.addTab(tabLayout.newTab().setText("Магнит"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                irContainer.visibility = View.GONE
                wifiContainer.visibility = View.GONE
                portContainer.visibility = View.GONE
                audioContainer.visibility = View.GONE
                magnetContainer.visibility = View.GONE
                bottomBar.visibility = View.GONE

                val fade = android.view.animation.AnimationUtils.loadAnimation(this@MainActivity, android.R.anim.fade_in)
                fade.duration = 300

                when (tab?.position) {
                    0 -> { irContainer.visibility = View.VISIBLE; irContainer.startAnimation(fade); bottomBar.visibility = View.VISIBLE }
                    1 -> { wifiContainer.visibility = View.VISIBLE; wifiContainer.startAnimation(fade) }
                    2 -> { portContainer.visibility = View.VISIBLE; portContainer.startAnimation(fade); fillGatewayIfEmpty() }
                    3 -> {
                        audioContainer.visibility = View.VISIBLE
                        audioContainer.startAnimation(fade)
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startAudioDetector()
                        } else {
                            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        }
                    }
                    4 -> {
                        magnetContainer.visibility = View.VISIBLE
                        magnetContainer.startAnimation(fade)
                        startMagnetometer()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        btnToggleIr.setOnClickListener {
            isIrRunning = !isIrRunning
            btnToggleIr.text = if (isIrRunning) "СТОП" else "СТАРТ"
            irStatus.text = if (isIrRunning) "Поиск..." else "Ожидание"
            irHint.visibility = if (isIrRunning) View.GONE else View.VISIBLE
            if (!isIrRunning) {
                overlayView.setSpots(emptyList())
                if (irDetectionCount > 0) autoSaveToHistory("ИК-детектор")
            }
        }

        btnToggleIr.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }
            false
        }

        btnReset.setOnClickListener {
            overlayView.setSpots(emptyList())
            irStatus.text = "Сброшено"
        }

        btnTorch.setOnClickListener {
            camera?.let { cam ->
                isTorchOn = !isTorchOn
                cam.cameraControl.enableTorch(isTorchOn)
                if (isTorchOn) {
                    btnTorch.setIconTintResource(android.R.color.holo_orange_light)
                } else {
                    btnTorch.setIconTintResource(android.R.color.white)
                }
            }
        }

        btnNight.setOnClickListener {
            isNightMode = !isNightMode
            nightFilter.visibility = if (isNightMode) View.VISIBLE else View.GONE
            btnNight.text = if (isNightMode) "ДЕНЬ" else "НОЧЬ"
        }

        btnCapture.setOnClickListener { takePhoto() }

        btnScanWifi.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA))
            } else {
                wifiStatus.text = "Сканирование..."
                wifiList.text = ""
                wifiScanner.startScan()
            }
        }

        btnScanPort.setOnClickListener {
            val ip = etGateway.text.toString().trim()
            if (ip.isBlank() || !ip.contains(".")) {
                portStatus.text = "Введите корректный IP"
                return@setOnClickListener
            }
            portStatus.text = "Проверка $ip:554..."
            Thread {
                val open = try {
                    Socket().use { socket -> socket.connect(InetSocketAddress(ip, 554), 800) }
                    true
                } catch (_: Exception) { false }
                runOnUiThread {
                    lastPortResult = if (open) "Порт 554 ОТКРЫТ на $ip. ОПАСНО!" else "Порт 554 закрыт на $ip."
                    portStatus.text = lastPortResult
                    autoSaveToHistory("Порт 554")
                }
            }.start()
        }

        btnHistory.setOnClickListener {
            val prefs = getSharedPreferences("history_prefs", MODE_PRIVATE)
            val all = prefs.all.filterKeys { it.startsWith("report|") }
            if (all.isEmpty()) {
                Toast.makeText(this, "История пуста", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val items = all.map { (key, value) ->
                val date = key.substringAfter("|")
                "📅 $date\n$value"
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("📋 История проверок")
                .setItems(items, null)
                .setPositiveButton("Закрыть", null)
                .show()
        }

        btnHelp.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("📖 Инструкция по использованию")
                .setMessage("""
                    Добро пожаловать в IR SCANNER! Это приложение для поиска скрытых камер, микрофонов и других устройств. Вот как пользоваться каждой вкладкой:

                    🔴 ИК-ДЕТЕКТОР (главная вкладка)
                    • Что это: поиск инфракрасных лучей, которые излучают камеры ночного видения.
                    • Как использовать: Нажмите «СТАРТ» и водите телефоном по комнате (по стенам, углам, подозрительным местам).
                    • Что видно: Красные пятна на экране — это возможные ИК-диоды. Чем ярче и больше пятно, тем ближе/сильнее камера.
                    • Настройка: Сверху есть ползунок «ЧУВСТВИТЕЛЬНОСТЬ». Двигайте его, чтобы настроить под освещение.
                    • Совет: Если пятен слишком много (шум), уменьшите чувствительность.

                    📶 WI-FI
                    • Что это: поиск сетей, которые могут принадлежать скрытым камерам.
                    • Как использовать: Нажмите «СКАНИРОВАТЬ». Через пару секунд появится список сетей.
                    • Что искать: Названия с «камера», «IPCam», «XM», а также сети с сильным сигналом, но без пароля.

                    🌐 ПОРТ
                    • Что это: проверка стандартного порта IP-камер (554, RTSP).
                    • Как использовать: Введите IP-адрес роутера (обычно 192.168.1.1). Нажмите «ПРОВЕРИТЬ».
                    • Что означает: Если порт открыт, возможно, в вашей сети есть IP-камера.

                    🎤 ЗВУК
                    • Что это: поиск микрофонов (жучков).
                    • Как использовать: Поднесите телефон к подозрительному месту. Смотрите на шкалу.
                    • Что означает: Если шкала резко подскакивает без внешних звуков — возможно, микрофон.

                    🧲 МАГНИТ
                    • Что это: поиск металлических деталей и магнитов.
                    • Как использовать: Водите телефоном вокруг подозрительных предметов.
                    • Что означает: Норма — 30–60 мкТл. Скачок до 100+ — рядом металл или магнит.

                    📸 ФОТО — снимок с камеры в галерею.
                    💡 ФОНАРИК — включает вспышку.
                    🌙 НОЧЬ — зеленоватый фильтр для контраста.
                    📋 ИСТОРИЯ — все проверки сохраняются автоматически.
                """.trimIndent())
                .setPositiveButton("Понятно", null)
                .show()
        }

        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    // АВТОСОХРАНЕНИЕ В ИСТОРИЮ
    private fun autoSaveToHistory(source: String) {
        val now = System.currentTimeMillis()
        if (now - lastSaveTime < saveIntervalMs) return
        lastSaveTime = now

        val prefs = getSharedPreferences("history_prefs", MODE_PRIVATE)
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val reportText = """
            [$source]
            ИК: $irDetectionCount пятен
            Wi-Fi: $lastWifiResults
            Порт: $lastPortResult
            Звук: $lastAudioValue
            Магнит: $lastMagnetValue
        """.trimIndent()

        prefs.edit().putString("report|$date", reportText).apply()

        // Ограничиваем историю последними 50 записями
        val all = prefs.all.filterKeys { it.startsWith("report|") }.toList().sortedBy { it.first }
        if (all.size > 50) {
            val toRemove = all.take(all.size - 50)
            val editor = prefs.edit()
            toRemove.forEach { editor.remove(it.first) }
            editor.apply()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioDetector() {
        if (isRecording) return
        try {
            val minBufSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufSize
            )
            audioRecord?.startRecording()
            isRecording = true
            audioThread = Thread {
                val buffer = ShortArray(minBufSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += buffer[i].toDouble() * buffer[i].toDouble()
                        }
                        val rms = sqrt(sum / read)
                        val db = 20 * log10(rms / 32768.0 + 1e-10)
                        val level = ((db + 100) / 100 * 100).toInt().coerceIn(0, 100)
                        runOnUiThread {
                            audioCircle.progress = level
                            lastAudioValue = "${db.toInt()} dB"
                            audioValue.text = lastAudioValue
                            if (level > 80) autoSaveToHistory("Громкий звук")
                        }
                    }
                }
            }
            audioThread?.start()
        } catch (e: Exception) {
            Log.e("Audio", "Ошибка", e)
        }
    }

    private fun stopAudioDetector() {
        isRecording = false
        audioThread?.join(1000)
        audioThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
    }

    private fun startMagnetometer() {
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopMagnetometer() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            runOnUiThread {
                magnetCircle.progress = magnitude.toInt().coerceIn(0, 200)
                lastMagnetValue = String.format("%.0f мкТл", magnitude)
                magnetValue.text = lastMagnetValue
                if (magnitude > 100) autoSaveToHistory("Магнитный скачок")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun takePhoto() {
        val imageCapture = this.imageCapture ?: return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IR_$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/IRCameraDetector")
            }
        }
        val outputUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(contentResolver, outputUri!!, contentValues).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Фото сохранено!", Toast.LENGTH_SHORT).show() }
                }
                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Ошибка: ${exception.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        )
    }

    private fun fillGatewayIfEmpty() {
        if (etGateway.text.isNullOrBlank()) {
            try {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                val gatewayInt = wm.dhcpInfo.gateway
                if (gatewayInt != 0) {
                    val ip = String.format("%d.%d.%d.%d", gatewayInt and 0xff, gatewayInt shr 8 and 0xff, gatewayInt shr 16 and 0xff, gatewayInt shr 24 and 0xff)
                    etGateway.setText(ip)
                }
            } catch (e: Exception) {
                Log.e("Gateway", "Не удалось", e)
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder().build()
                analyzer = IRSpotAnalyzer(190) { spots ->
                    runOnUiThread {
                        if (!isIrRunning) return@runOnUiThread
                        val viewW = overlayView.width.toFloat()
                        val viewH = overlayView.height.toFloat()
                        val imgW = previewView.width.toFloat()
                        val imgH = previewView.height.toFloat()
                        val scaleX = if (imgW > 0) viewW / imgW else 1f
                        val scaleY = if (imgH > 0) viewH / imgH else 1f
                        val ui = spots.map { s -> OverlayView.Spot(s.x * scaleX, s.y * scaleY, s.intensity) }
                        overlayView.setSpots(ui)
                        irStatus.text = if (ui.isEmpty()) "Пятен нет" else "ИК: ${ui.size}"
                        irDetectionCount = ui.size
                        if (ui.isNotEmpty()) vibrateOnce()
                    }
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, analyzer!!) }

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, imageCapture
                )
                irStatus.text = "Готов"
                btnToggleIr.isEnabled = true
            } catch (e: Exception) {
                Log.e("Camera", "Ошибка", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private var lastVib = 0L
    private fun vibrateOnce() {
        val now = System.currentTimeMillis()
        if (now - lastVib < 500) return
        lastVib = now
        val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") v.vibrate(50)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        wifiScanner.stop()
        stopAudioDetector()
        stopMagnetometer()
    }
}