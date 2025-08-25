#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <cmath>
#include <atomic>

// --- 1. Include the KissFFT headers ---
// use kiss_fftr.h for real-to-complex and complex-to-real transforms
#include "kiss_fftr.h"

#define TAG "HearWell"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

class SimplePlayer : public oboe::AudioStreamCallback {
public:

    std::shared_ptr<oboe::AudioStream> stream;
    double phase = 0.0;
    std::atomic<bool> mIsPlaying{false};
    std::atomic<double> mFrequency{440.0}; // Default frequency

    std::atomic<double> mVolume{0.5};  // Default volume (0.0 to 1.0)
    void start(double frequency) {
        if (mIsPlaying) {
            stop();
        }

        mFrequency = frequency;
        phase = 0.0;

        if (stream) {
            stream->close();
            stream.reset();
        }

        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Exclusive)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(oboe::ChannelCount::Mono)
                ->setCallback(this);

        oboe::Result result = builder.openStream(stream);
        if (result != oboe::Result::OK) {
            LOGE("Stream open failed: %s", oboe::convertToText(result));
            return;
        }

        mIsPlaying = true;

        result = stream->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Stream start failed: %s", oboe::convertToText(result));
            stream->close();
            stream.reset();
            mIsPlaying = false;
        }
    }

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* audioStream,
            void* audioData,
            int32_t numFrames) override {

        if (!mIsPlaying) return oboe::DataCallbackResult::Stop;

        // make the audio processing logic here
        float *outputBuffer = static_cast<float *>(audioData);
        const double amplitude = mVolume.load();
        const double sampleRate = audioStream->getSampleRate();
        const double currentFreq = mFrequency.load();
        const double phaseIncrement = currentFreq * 2.0 * M_PI / sampleRate;

        for (int i = 0; i < numFrames; i++) {
            outputBuffer[i] = static_cast<float>(amplitude * sin(phase));
            phase += phaseIncrement;
        }

        if (phase > 2.0 * M_PI) {
            phase -= 2.0 * M_PI;
        }

        return oboe::DataCallbackResult::Continue;
    }

    void stop() {
        if (stream && mIsPlaying) {
            stream->requestStop();
            stream->close();
            stream.reset();
            mIsPlaying = false;
        }
    }
};

SimplePlayer player;

class RealTimeProcessor : public oboe::AudioStreamCallback {
public:
    RealTimeProcessor(int32_t bufferSize, int32_t sampleRate) : mBufferSize(bufferSize) {
        // --- This section runs ONLY ONCE when the object is created ---

        // Hamming window initialisation
        mWindow.resize(mBufferSize);
        for (int i = 0; i < mBufferSize; ++i) {
            mWindow[i] = 0.54f - 0.46f * cosf(2.0f * M_PI * static_cast<float>(i) / (mBufferSize - 1));
        }

        // Allocate a buffer to hold the audio after the window is applied.
        mWindowedInput.resize(mBufferSize);

        // Allocate a buffer for the FFT's output. The output of a real FFT has (N/2 + 1) complex values.
        mFftOutput.resize(mBufferSize / 2 + 1);

        // Create the forward FFT (real audio -> complex frequencies).
        mFftCfg = kiss_fftr_alloc(mBufferSize, 0, nullptr, nullptr);

        // Create the inverse FFT (complex frequencies -> real audio).
        mIfftCfg = kiss_fftr_alloc(mBufferSize, 1, nullptr, nullptr);
    }

    ~RealTimeProcessor() {
        kiss_fftr_free(mFftCfg);
        kiss_fftr_free(mIfftCfg);
    }


    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames) override {

        auto* floatData = static_cast<float*>(audioData);

        // 1. Apply Hamming window
        for (int i = 0; i < numFrames; ++i) {
            mWindowedInput[i] = floatData[i] * mWindow[i];
        }

        // 2. Perform FFT
        kiss_fftr(mFftCfg, mWindowedInput.data(), mFftOutput.data());

        // 3. Apply gain to desired frequencies (Your logic goes here)
        // 0double the volume of the first few frequency bins
        for (int i = 0; i < 10; ++i) {
            mFftOutput[i].r *= 2.0f;
            mFftOutput[i].i *= 2.0f;
        }

        // 4. Perform inverse FFT (IFFT)
        // The result will be placed back into the original floatData buffer.
        kiss_fftri(mIfftCfg, mFftOutput.data(), floatData);

        // 5. Normalize the output
        // divide by the buffer size to restore the original amplitude.
        for (int i = 0; i < numFrames; ++i) {
            floatData[i] /= numFrames;
        }

        // mOutputStream->write(floatData, numFrames, 0);

        return oboe::DataCallbackResult::Continue;
    }

private:
    const int32_t mBufferSize;

    std::vector<float> mWindow;
    std::vector<float> mWindowedInput;
    std::vector<kiss_fft_cpx> mFftOutput;

    kiss_fftr_cfg mFftCfg;
    kiss_fftr_cfg mIfftCfg;
};

extern "C"
JNIEXPORT void JNICALL
Java_com_example_equalizer_AudioEqualizer_startOboe(JNIEnv *env, jobject thiz, jdouble frequency) {
    player.start(static_cast<double>(frequency));
    __android_log_print(ANDROID_LOG_INFO, "NativeCode", "startOboe called with freq: %f", frequency);
}