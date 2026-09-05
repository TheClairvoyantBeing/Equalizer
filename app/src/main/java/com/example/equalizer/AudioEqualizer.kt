package com.example.equalizer

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Represents an individual parametric EQ band (center frequency + gain).
 */
data class EqBand(
    val freq: Float,        // Center frequency in Hz
    var gainDb: Float       // Gain in dB (-12 dB to +12 dB)
)

/**
 * High-performance Transposed Direct Form II (TDF-II) Peaking Biquad Filter.
 * Minimizes coefficient quantization noise and arithmetic memory footprint.
 */
class BiquadPeakingEQ(
    var sampleRate: Int,
    var band: EqBand
) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    // TDF-II state registers
    private var s1 = 0.0
    private var s2 = 0.0

    fun updateCoefficients(q: Double = 1.2) {
        val omega = 2.0 * Math.PI * band.freq / sampleRate
        val sinOmg = sin(omega)
        val cosOmg = cos(omega)
        val aVal = 10.0.pow(band.gainDb / 40.0)
        val alpha = sinOmg / (2.0 * q)

        val a0 = 1.0 + alpha / aVal
        b0 = (1.0 + alpha * aVal) / a0
        b1 = (-2.0 * cosOmg) / a0
        b2 = (1.0 - alpha * aVal) / a0
        a1 = (-2.0 * cosOmg) / a0
        a2 = (1.0 - alpha / aVal) / a0
    }

    /**
     * Processes a single audio sample using Transposed Direct Form II.
     */
    fun process(sampleIn: Float): Float {
        val x = sampleIn.toDouble()
        val y = b0 * x + s1
        s1 = b1 * x - a1 * y + s2
        s2 = b2 * x - a2 * y
        return y.toFloat()
    }

    fun reset() {
        s1 = 0.0
        s2 = 0.0
    }
}

/**
 * Real-time 7-band parametric audio equalizer engine.
 */
class AudioEqualizer(val sampleRate: Int = 48000) {

    // 7 standard ISO frequency bands
    val bands = listOf(
        EqBand(62.5f,   0f),   // Sub-bass
        EqBand(187.5f,  0f),   // Bass
        EqBand(375f,    0f),   // Low-mid
        EqBand(750f,    0f),   // Mid
        EqBand(1500f,   0f),   // Upper-mid
        EqBand(3000f,   0f),   // Presence
        EqBand(6000f,   0f)    // Brilliance
    )

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(2048)
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    @Volatile
    var running = false
        private set

    @Volatile
    var isMuted = false

    private val filters = mutableListOf<BiquadPeakingEQ>()

    init {
        bands.forEach {
            val filter = BiquadPeakingEQ(sampleRate, it)
            filter.updateCoefficients(q = 1.2)
            filters.add(filter)
        }
    }

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in filters.indices) return
        val clampedGain = gainDb.coerceIn(-12f, 12f)
        bands[bandIndex].gainDb = clampedGain
        filters[bandIndex].updateCoefficients(q = 1.2)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running) return
        running = true
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
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
            Log.e("AudioEqualizer", "AudioRecord initialization failed!")
            running = false
            return
        }

        audioRecord?.startRecording()
        audioTrack?.play()

        thread(name = "AudioEqualizerDSPThread", priority = Thread.MAX_PRIORITY) {
            // Set urgent audio Linux thread priority to prevent kernel preemption
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val audioBuffer = ShortArray(bufferSize / 2)
            val floatBuffer = FloatArray(audioBuffer.size)
            val processedBuffer = ShortArray(audioBuffer.size)

            while (running) {
                val read = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (read > 0) {
                    if (isMuted) {
                        // Acoustic feedback guard active
                        audioTrack?.write(ShortArray(read), 0, read)
                        continue
                    }

                    // Convert short to float [-1.0f, 1.0f]
                    for (i in 0 until read) {
                        floatBuffer[i] = audioBuffer[i] / 32768.0f
                    }

                    // Apply cascading 7-band biquad filters
                    for (i in 0 until read) {
                        var sample = floatBuffer[i]
                        for (f in filters) {
                            sample = f.process(sample)
                        }
                        // Anti-clipping saturation guard
                        processedBuffer[i] = (sample.coerceIn(-1.0f, 1.0f) * 32767).toInt().toShort()
                    }

                    // Stream processed PCM buffer to audio hardware
                    audioTrack?.write(processedBuffer, 0, read)
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w("AudioEqualizer", "Error stopping AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
        }

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w("AudioEqualizer", "Error stopping AudioTrack: ${e.message}")
        } finally {
            audioTrack = null
        }
    }
}