#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cmath>
#include <cstring>
#include <memory>
#include <string>
#include <vector>
#include <thread>

#include "rife.h"
#include "gpu.h"
#include "mat.h"

#define LOG_TAG "FrameGenAI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    bool gpuInstanceCreated = false;
    std::unique_ptr<RIFE> rife;
    int width = 0;
    int height = 0;
    std::vector<unsigned char> rgb0;
    std::vector<unsigned char> rgb1;
    std::vector<unsigned char> rgbaOut;
    std::vector<unsigned char> rgbOut;
};

static bool get_direct(JNIEnv* env, jobject buffer, void** ptr, jlong requiredBytes) {
    *ptr = env->GetDirectBufferAddress(buffer);
    if (!*ptr) {
        LOGE("Input buffer is not a direct ByteBuffer");
        return false;
    }

    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (capacity < requiredBytes) {
        LOGE("Buffer too small: %lld < %lld",
             static_cast<long long>(capacity),
             static_cast<long long>(requiredBytes));
        return false;
    }
    return true;
}

static void rgba_to_rgb(const unsigned char* rgba, unsigned char* rgb, int pixelCount) {
    for (int i = 0; i < pixelCount; ++i) {
        rgb[i * 3 + 0] = rgba[i * 4 + 0];
        rgb[i * 3 + 1] = rgba[i * 4 + 1];
        rgb[i * 3 + 2] = rgba[i * 4 + 2];
    }
}

static void rgb_to_rgba(const unsigned char* rgb, unsigned char* rgba, int pixelCount) {
    for (int i = 0; i < pixelCount; ++i) {
        rgba[i * 4 + 0] = rgb[i * 3 + 0];
        rgba[i * 4 + 1] = rgb[i * 3 + 1];
        rgba[i * 4 + 2] = rgb[i * 3 + 2];
        rgba[i * 4 + 3] = 255;
    }
}

static float sample_luma_rgb(
    const unsigned char* rgb,
    int pixelCount,
    int sampleIndex,
    int sampleStride
) {
    if (pixelCount <= 0) return 0.0f;
    const int i = std::clamp(sampleIndex * sampleStride, 0, pixelCount - 1);
    const float r = rgb[i * 3 + 0] / 255.0f;
    const float g = rgb[i * 3 + 1] / 255.0f;
    const float b = rgb[i * 3 + 2] / 255.0f;
    return 0.2126f * r + 0.7152f * g + 0.0722f * b;
}

static void compute_luma_stats(
    const unsigned char* rgb,
    int pixelCount,
    float& mean,
    float& rms,
    float& darkFloor
) {
    mean = 0.0f;
    rms = 0.0f;
    darkFloor = 0.0f;

    if (pixelCount <= 0) return;

    constexpr int stride = 32;
    constexpr int maxSamples = 4096;

    double sum = 0.0;
    double sumSq = 0.0;
    float minPositive = 1.0f;
    int count = 0;

    const int samples = std::min(maxSamples, (pixelCount + stride - 1) / stride);
    for (int s = 0; s < samples; ++s) {
        const float l = sample_luma_rgb(rgb, pixelCount, s, stride);
        sum += l;
        sumSq += static_cast<double>(l) * l;
        if (l > 0.015f) minPositive = std::min(minPositive, l);
        ++count;
    }

    if (count == 0) return;

    mean = static_cast<float>(sum / count);
    rms = static_cast<float>(std::sqrt(sumSq / count));
    darkFloor = minPositive;
}

static void match_luminance(
    unsigned char* rgb,
    int pixelCount,
    float targetMean,
    float targetRms,
    float sourceMean
) {
    if (pixelCount <= 0) return;

    float outputMean = 0.0f;
    float outputRms = 0.0f;
    float outputDarkFloor = 0.0f;
    compute_luma_stats(rgb, pixelCount, outputMean, outputRms, outputDarkFloor);

    if (outputMean < 0.008f || targetMean < 0.008f) {
        return;
    }

    // Use both mean and RMS so a darker-than-source neural result is lifted
    // even when the scene contains large black areas.
    const float meanGain = targetMean / outputMean;
    const float rmsGain = targetRms / std::max(outputRms, 0.008f);
    float gain = 0.60f * meanGain + 0.40f * rmsGain;

    // Do not clamp so tightly that genuinely dark neural frames remain dark.
    // Keep the bounds finite to avoid pathological amplification.
    gain = std::clamp(gain, 0.55f, 2.20f);

    // Small bias correction for crushed blacks. This is intentionally tiny so
    // it does not turn a genuinely black game scene gray.
    const float targetBlack = std::clamp(sourceMean * 0.015f, 0.0f, 0.012f);
    const float outputBlack = std::clamp(outputDarkFloor, 0.0f, 0.02f);
    const float bias = targetBlack - outputBlack * gain;

    for (int i = 0; i < pixelCount; ++i) {
        for (int c = 0; c < 3; ++c) {
            const float v = rgb[i * 3 + c] / 255.0f;
            const float corrected = std::clamp(v * gain + bias, 0.0f, 1.0f);
            rgb[i * 3 + c] = static_cast<unsigned char>(
                std::lround(corrected * 255.0f)
            );
        }
    }
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_framegen_AiFrameGenerator_nativeCreate(
    JNIEnv* env,
    jobject,
    jstring jModelDirectory,
    jint gpuIndex
) {
    const char* modelChars = env->GetStringUTFChars(jModelDirectory, nullptr);
    if (!modelChars) return 0;

    std::string modelDir(modelChars);
    env->ReleaseStringUTFChars(jModelDirectory, modelChars);

    // NCNN's Vulkan GPU enumeration requires an explicit process-level
    // GPU instance before get_gpu_count/get_default_gpu_index are reliable.
    if (ncnn::create_gpu_instance() != 0) {
        LOGE("ncnn::create_gpu_instance failed");
        return 0;
    }

    int requestedGpu = static_cast<int>(gpuIndex);
    if (requestedGpu == -2) {
        requestedGpu = ncnn::get_gpu_count() > 0
            ? ncnn::get_default_gpu_index()
            : -1;
    }

    auto engine = std::make_unique<Engine>();
    engine->gpuInstanceCreated = true;
    int cpuThreads = static_cast<int>(std::thread::hardware_concurrency());
    if (cpuThreads <= 0) cpuThreads = 4;
    // RIFE's num_threads controls its CPU helper threads. Keep it modest so the
    // render thread and Android system remain responsive on mobile.
    cpuThreads = std::clamp(cpuThreads, 2, 4);

    engine->rife = std::make_unique<RIFE>(
        requestedGpu,
        false,
        false,
        false,
        cpuThreads,
        false,
        true
    );

    if (engine->rife->load(modelDir) != 0) {
        LOGE("RIFE model load failed: %s", modelDir.c_str());
        if (engine->gpuInstanceCreated) {
            ncnn::destroy_gpu_instance();
        }
        return 0;
    }

    LOGI("RIFE v4.6 neural backend initialized on Vulkan GPU %d with %d CPU helper threads", requestedGpu, cpuThreads);
    return reinterpret_cast<jlong>(engine.release());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_framegen_AiFrameGenerator_nativeInterpolate(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject previous,
    jobject current,
    jobject output,
    jint width,
    jint height,
    jfloat timestep
) {
    auto* engine = reinterpret_cast<Engine*>(handle);
    if (!engine || width <= 0 || height <= 0) return -1;

    static std::atomic<int> successCount{0};

    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    const jlong rgbaBytes = static_cast<jlong>(pixels * 4u);

    void* prevPtr = nullptr;
    void* currPtr = nullptr;
    void* outPtr = nullptr;

    if (!get_direct(env, previous, &prevPtr, rgbaBytes) ||
        !get_direct(env, current, &currPtr, rgbaBytes) ||
        !get_direct(env, output, &outPtr, rgbaBytes)) {
        return -2;
    }

    if (engine->width != width || engine->height != height) {
        engine->width = width;
        engine->height = height;
        engine->rgb0.resize(pixels * 3u);
        engine->rgb1.resize(pixels * 3u);
        engine->rgbaOut.resize(pixels * 4u);
        engine->rgbOut.resize(pixels * 3u);
    }

    rgba_to_rgb(
        static_cast<const unsigned char*>(prevPtr),
        engine->rgb0.data(),
        static_cast<int>(pixels)
    );
    rgba_to_rgb(
        static_cast<const unsigned char*>(currPtr),
        engine->rgb1.data(),
        static_cast<int>(pixels)
    );

    // The active NCNN configuration uses FP16 storage + INT8 storage.
    // In that mode upstream RIFE directly wraps tightly packed RGB8 memory.
    // This avoids extra full-frame allocations and matches process_v4's
    // expected pixel layout on Vulkan.
    ncnn::Mat in0(
        width,
        height,
        static_cast<void*>(engine->rgb0.data()),
        static_cast<size_t>(3),
        1,
        nullptr
    );
    ncnn::Mat in1(
        width,
        height,
        static_cast<void*>(engine->rgb1.data()),
        static_cast<size_t>(3),
        1,
        nullptr
    );
    ncnn::Mat outimage(
        width,
        height,
        static_cast<void*>(engine->rgbOut.data()),
        static_cast<size_t>(3),
        1,
        nullptr
    );

    float mean0 = 0.0f;
    float rms0 = 0.0f;
    float black0 = 0.0f;
    float mean1 = 0.0f;
    float rms1 = 0.0f;
    float black1 = 0.0f;

    compute_luma_stats(
        engine->rgb0.data(),
        static_cast<int>(pixels),
        mean0,
        rms0,
        black0
    );
    compute_luma_stats(
        engine->rgb1.data(),
        static_cast<int>(pixels),
        mean1,
        rms1,
        black1
    );

    const float targetMean = 0.5f * (mean0 + mean1);
    const float targetRms = 0.5f * (rms0 + rms1);
    const float sourceMean = targetMean;

    const int ret = engine->rife->process_v4(
        in0,
        in1,
        std::clamp(static_cast<float>(timestep), 0.01f, 0.99f),
        outimage
    );

    if (ret != 0) {
        LOGE("RIFE process returned %d", ret);
        return ret;
    }

    // RIFE wrote its interleaved RGB8 result directly into rgbOut.
    match_luminance(
        engine->rgbOut.data(),
        static_cast<int>(pixels),
        targetMean,
        targetRms,
        sourceMean
    );

    rgb_to_rgba(
        engine->rgbOut.data(),
        static_cast<unsigned char*>(outPtr),
        static_cast<int>(pixels)
    );

    const int count = ++successCount;
    if ((count % 30) == 0) {
        LOGI("RIFE AI generated %d intermediate frames", count);
    }

    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_framegen_AiFrameGenerator_nativeRelease(
    JNIEnv*,
    jobject,
    jlong handle
) {
    auto* engine = reinterpret_cast<Engine*>(handle);
    if (!engine) return;
    const bool destroyGpu = engine->gpuInstanceCreated;
    delete engine;
    if (destroyGpu) {
        ncnn::destroy_gpu_instance();
    }
}
