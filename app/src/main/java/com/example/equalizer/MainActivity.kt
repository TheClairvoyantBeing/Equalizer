package com.example.equalizer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var equalizer: AudioEqualizer

    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    // New permission launcher for RECORD_AUDIO permission
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted - start equalizer
            equalizer.start()
            updateButtonStates(isRunning = true)
        } else {
            // Permission denied - optionally notify user or disable features
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            0
        )
        // Initialize Equalizer instance
        equalizer = AudioEqualizer()

        // Create a vertical LinearLayout to hold buttons
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 100, 50, 100) // padding around the layout
        }

        // Create Start EQ button
        startButton = Button(this).apply {
            text = "Start EQ"
            isEnabled = true
            setOnClickListener { onStartButtonClicked() }
        }

        // Create Stop EQ button
        stopButton = Button(this).apply {
            text = "Stop EQ"
            isEnabled = false
            setOnClickListener { onStopButtonClicked() }
        }

        // Add buttons to the layout with some spacing below the Start button
        layout.addView(
            startButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 30
            }
        )
        layout.addView(
            stopButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // Set the activity content to our layout with buttons
        setContentView(layout)

        // ---- SET YOUR INITIAL GAINS HERE (example) ----
        // Using setBandGain() so you can tune each band before starting
        equalizer.setBandGain(0, 6f)   // Boost Sub-bass by +6dB
        equalizer.setBandGain(1, 0f)   // Bass at 0dB (no change)
        equalizer.setBandGain(2, -4f)  // Cut Low-mid by -4dB
        equalizer.setBandGain(3, 0f)   // Mid at 0dB
        equalizer.setBandGain(4, 3f)   // Boost Upper-mid by +3dB
        equalizer.setBandGain(5, 0f)   // Presence at 0dB
        equalizer.setBandGain(6, 2f)   // Boost Brilliance by +2dB
        // -----------------------------------------------
    }

    // Handle Start button click
    private fun onStartButtonClicked() {
        if (checkAudioPermission()) {
            // Permission already granted, start AudioEqualizer immediately
            equalizer.start()
            updateButtonStates(isRunning = true)
        } else {
            // Request permission from user
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Handle Stop button click
    private fun onStopButtonClicked() {
        equalizer.stop()
        updateButtonStates(isRunning = false)
    }

    // Enable/disable buttons based on running state
    private fun updateButtonStates(isRunning: Boolean) {
        startButton.isEnabled = !isRunning
        stopButton.isEnabled = isRunning
    }

    // Check if microphone permission is granted
    private fun checkAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        equalizer.stop()
    }
}