package com.example.equalizer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Main Activity providing interactive 7-band graphic equalizer controls,
 * real-time parameter tuning, acoustic feedback prevention, and presets.
 */
class MainActivity : ComponentActivity() {

    private lateinit var equalizer: AudioEqualizer
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var muteButton: Button
    private lateinit var statusText: TextView

    private val bandSeekBars = mutableListOf<SeekBar>()
    private val bandLabels = mutableListOf<TextView>()

    // Broadcast receiver to detect headphone disconnection and guard against acoustic feedback
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (equalizer.running) {
                    equalizer.isMuted = true
                    muteButton.text = "Unmute (Auto-Muted: Headphones Unplugged)"
                    statusText.text = "Status: Auto-muted for acoustic safety"
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            equalizer.start()
            updateButtonStates(isRunning = true)
        } else {
            statusText.text = "Status: Microphone permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        equalizer = AudioEqualizer(sampleRate = 48000)

        val rootScrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }

        // Title Header
        val titleText = TextView(this).apply {
            text = "7-Band Parametric Audio Equalizer"
            textSize = 20f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 20)
        }
        mainLayout.addView(titleText)

        statusText = TextView(this).apply {
            text = "Status: Ready (48 kHz / 7-Band Biquad TDF-II)"
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 30)
        }
        mainLayout.addView(statusText)

        // Transport Controls Row (Start / Stop)
        val transportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        startButton = Button(this).apply {
            text = "Start Engine"
            setOnClickListener { onStartButtonClicked() }
        }
        val startParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 12
        }
        transportRow.addView(startButton, startParams)

        stopButton = Button(this).apply {
            text = "Stop Engine"
            isEnabled = false
            setOnClickListener { onStopButtonClicked() }
        }
        val stopParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 12
        }
        transportRow.addView(stopButton, stopParams)
        mainLayout.addView(transportRow)

        // Mute Guard Button
        muteButton = Button(this).apply {
            text = "Mute Engine"
            setOnClickListener {
                equalizer.isMuted = !equalizer.isMuted
                text = if (equalizer.isMuted) "Unmute Audio" else "Mute Audio"
                statusText.text = if (equalizer.isMuted) "Status: Audio Muted" else "Status: Running"
            }
        }
        val muteParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 16
            bottomMargin = 24
        }
        mainLayout.addView(muteButton, muteParams)

        // Presets Container
        val presetsTitle = TextView(this).apply {
            text = "EQ Presets:"
            textSize = 15f
            setPadding(0, 10, 0, 10)
        }
        mainLayout.addView(presetsTitle)

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }

        fun createPresetButton(label: String, gains: FloatArray): Button {
            return Button(this).apply {
                text = label
                textSize = 11f
                setOnClickListener { applyPreset(gains) }
            }
        }

        val flatPreset = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val bassPreset = floatArrayOf(6f, 4f, 2f, 0f, 0f, -1f, -2f)
        val vocalPreset = floatArrayOf(-2f, -1f, 1f, 4f, 3f, 1f, 0f)
        val treblePreset = floatArrayOf(-2f, -1f, 0f, 1f, 3f, 5f, 6f)

        val btnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        presetRow.addView(createPresetButton("Flat", flatPreset), btnParams)
        presetRow.addView(createPresetButton("Bass", bassPreset), btnParams)
        presetRow.addView(createPresetButton("Vocal", vocalPreset), btnParams)
        presetRow.addView(createPresetButton("Treble", treblePreset), btnParams)
        mainLayout.addView(presetRow)

        // 7-Band Sliders Section
        val bandsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 0)
        }

        val bandFrequencies = listOf("62.5 Hz", "187.5 Hz", "375 Hz", "750 Hz", "1.5 kHz", "3 kHz", "6 kHz")

        for (i in 0 until 7) {
            val bandContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
            }

            val currentGain = equalizer.bands[i].gainDb
            val label = TextView(this).apply {
                text = "${bandFrequencies[i]}: ${formatGain(currentGain)}"
                textSize = 13f
            }
            bandLabels.add(label)
            bandContainer.addView(label)

            val seekBar = SeekBar(this).apply {
                max = 24 // maps 0..24 to -12dB..+12dB
                progress = (currentGain + 12f).toInt().coerceIn(0, 24)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                        val gainDb = (prog - 12).toFloat()
                        equalizer.setBandGain(i, gainDb)
                        label.text = "${bandFrequencies[i]}: ${formatGain(gainDb)}"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            bandSeekBars.add(seekBar)
            bandContainer.addView(seekBar)

            bandsContainer.addView(bandContainer)
        }
        mainLayout.addView(bandsContainer)

        rootScrollView.addView(mainLayout)
        setContentView(rootScrollView)

        // Register noisy audio receiver
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(noisyReceiver, filter)
    }

    private fun formatGain(gainDb: Float): String {
        return if (gainDb > 0) "+${gainDb.toInt()} dB" else "${gainDb.toInt()} dB"
    }

    private fun applyPreset(gains: FloatArray) {
        for (i in gains.indices) {
            if (i < bandSeekBars.size) {
                val gain = gains[i]
                bandSeekBars[i].progress = (gain + 12f).toInt()
                equalizer.setBandGain(i, gain)
            }
        }
    }

    private fun onStartButtonClicked() {
        if (checkAudioPermission()) {
            equalizer.start()
            updateButtonStates(isRunning = true)
            statusText.text = "Status: Engine Running (Active Processing)"
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onStopButtonClicked() {
        equalizer.stop()
        updateButtonStates(isRunning = false)
        statusText.text = "Status: Engine Stopped"
    }

    private fun updateButtonStates(isRunning: Boolean) {
        startButton.isEnabled = !isRunning
        stopButton.isEnabled = isRunning
    }

    private fun checkAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {}
        equalizer.stop()
    }
}