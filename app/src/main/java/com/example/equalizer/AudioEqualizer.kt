package com.example.equalizer

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlin.concurrent.thread
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.cos

// Represents one EQ band (center freq + gain)
data class EqBand(
    val freq: Float,        // Center frequency in Hz
    var gainDb: Float       // Gain in dB (-12 to +12 typical)
)

// Parametric peak EQ filter (Biquad)
class BiquadPeakingEQ(
    var sampleRate: Int,
    var band: EqBand
) {
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun updateCoefficients(q: Double = 1.2) {
        val omega = 2.0 * Math.PI * band.freq / sampleRate
        val sinOmg = sin(omega)
        val cosOmg = cos(omega)
        val A = 10.0.pow(band.gainDb / 40.0)
        val alpha = sinOmg / (2.0 * q)

        b0 = 1 + alpha * A
        b1 = -2 * cosOmg
        b2 = 1 - alpha * A
        val a0 = 1 + alpha / A
        a1 = -2 * cosOmg
        a2 = 1 - alpha / A

        b0 /= a0
        b1 /= a0
        b2 /= a0
        a1 /= a0
        a2 /= a0
    }

    fun process(sampleIn: Float): Float {
        val x0 = sampleIn.toDouble()
        val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2

        x2 = x1
        x1 = x0
        y2 = y1
        y1 = y0

        return y0.toFloat()
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }
}


// The processing/IO/pipeline "engine"
class AudioEqualizer {

    // ---- Default band settings (customize here) ----
    private val bands = listOf(
        EqBand(62.5f,   6f),   // Sub-bass: +6dB
        EqBand(187.5f,  3f),   // Bass: +3dB
        EqBand(375f,    0f),   // Low-mid: 0dB
        EqBand(750f,   -4f),   // Mid: -4dB
        EqBand(1500f,   2f),   // Upper-mid: +2dB
        EqBand(3000f,   5f),   // Presence: +5dB
        EqBand(6000f,  -2f)    // Brilliance: -2dB
    )
    // ------------------------------------------------

    private val sampleRate = 48000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var running = false

    private val filters = mutableListOf<BiquadPeakingEQ>()

    init {
        bands.forEach {
            val filter = BiquadPeakingEQ(sampleRate, it)
            filter.updateCoefficients(q = 1.2)
            filters.add(filter)
        }
    }

    // Use this to change the gain for any band during runtime if needed
    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in filters.indices) return
        bands[bandIndex].gainDb = gainDb
        filters[bandIndex].updateCoefficients(q = 1.2)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running) return
        running = true
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConfig, audioFormat, bufferSize
        )
        audioTrack = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("EQ", "AudioRecord not initialized!")
            return
        }

        audioRecord?.startRecording()
        audioTrack?.play()


        thread(start = true) {
            val audioBuffer = ShortArray(bufferSize / 2)
            val floatBuffer = FloatArray(audioBuffer.size)
            val processedBuffer = ShortArray(audioBuffer.size)

            while (running) {
                val read = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (read > 0) {
                    // Convert short to float [-1.0f, 1.0f]
                    for (i in 0 until read) {
                        floatBuffer[i] = audioBuffer[i] / 32768.0f
                    }

                    // Apply EQ filters
                    for (i in 0 until read) {
                        var sample = floatBuffer[i]
                        filters.forEach { filter ->
                            sample = filter.process(sample)
                        }
                        // Clamp and convert back to short
                        processedBuffer[i] = (sample.coerceIn(-1.0f, 1.0f) * 32767).toInt().toShort()
                    }

                    // Write filtered audio
                    audioTrack?.write(processedBuffer, 0, read)
                    Log.d("EQ", "Processed $read samples")
                }
            }
        }
    }

    fun stop() {
        running = false
        audioRecord?.stop()
        audioRecord?.release()
        audioTrack?.stop()
        audioTrack?.release()
    }
}