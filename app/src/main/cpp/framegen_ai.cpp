#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
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
    std::unique_ptr<RIFE> rife;
    int width = 0;
    int height = 0;
    std::vector<unsigned char> rgb0;
    std::vector<unsigned char> rgb1;
    std::vector<unsigned char> rgbaOut;
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

static float average_luma_rgb(const unsigned char* rgb, int pixelCount) {
    if (pixelCount <= 0) return 0.0f;

    // Sample every 16th pixel to keep the correction cheap on mobile CPUs.
    const int stride = 16;
    double sum = 0.0;
    int count = 0;

    for (int i = 0; i < pixelCount; i += stride) {
        const float r = rgb[i * 3 + 0] / 255.0f;
        const float g = rgb[i * 3 + 1] / 255.0f;
        const float b = rgb[i * 3 + 2] / 255.0f;
        sum += 0.2126 * r + 0.7152 * g + 0.0722 * b;
        ++count;
    }

    return count > 0 ? static_cast<float>(sum / count) : 0.0f;
}

static void match_luminance(
    unsigned char* rgb,
    int pixelCount,
    float targetLuma
) {
    const float sourceLuma = average_luma_rgb(rgb, pixelCount);

    if (sourceLuma < 0.01f || targetLuma < 0.01f) {
        return;
    }

    // Match the global luminance of the neural output to the average of the
    // two real source frames. Clamp the correction to avoid over-amplifying
    // genuinely darker/brighter scenes.
    float gain = targetLuma / sourceLuma;
    gain = std::clamp(gain, 0.72f, 1.38f);

    for (int i = 0; i < pixelCount; ++i) {
        rgb[i * 3 + 0] = static_cast<unsigned char>(
            std::clamp(std::lround(rgb[i * 3 + 0] * gain), 0L, 255L)
        );
        rgb[i * 3 + 1] = static_cast<unsigned char>(
            std::clamp(std::lround(rgb[i * 3 + 1] * gain), 0L, 255L)
        );
        rgb[i * 3 + 2] = static_cast<unsigned char>(
            std::clamp(std::lround(rgb[i * 3 + 2] * gain), 0L, 255L)
        );
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

    int requestedGpu = static_cast<int>(gpuIndex);
    if (requestedGpu == -2) {
        requestedGpu = ncnn::get_gpu_count() > 0
            ? ncnn::get_default_gpu_index()
            : -1;
    }

    auto engine = std::make_unique<Engine>();
    int cpuThreads = static_cast<int>(std::thread::hardware_concurrency());
    if (cpuThreads <= 0) cpuThreads = 4;
    cpuThreads = std::clamp(cpuThreads, 2, 6);

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

    ncnn::Mat in0 = ncnn::Mat::from_pixels(
        engine->rgb0.data(),
        ncnn::Mat::PIXEL_RGB,
        width,
        height
    );
    ncnn::Mat in1 = ncnn::Mat::from_pixels(
        engine->rgb1.data(),
        ncnn::Mat::PIXEL_RGB,
        width,
        height
    );

    // 3-channel RGB8 output buffer, matching RIFE's sample usage.
    ncnn::Mat outimage(
        width,
        height,
        3,
        static_cast<size_t>(3),
        nullptr
    );

    const float targetLuma =
        0.5f * (
            average_luma_rgb(engine->rgb0.data(), static_cast<int>(pixels)) +
            average_luma_rgb(engine->rgb1.data(), static_cast<int>(pixels))
        );

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

    outimage.to_pixels(
        engine->rgb0.data(),
        ncnn::Mat::PIXEL_RGB
    );

    // Prevent the AI intermediate frame from causing a visible global
    // brightness jump/dip relative to the real game frames.
    match_luminance(
        engine->rgb0.data(),
        static_cast<int>(pixels),
        targetLuma
    );

    rgb_to_rgba(
        engine->rgb0.data(),
        static_cast<unsigned char*>(outPtr),
        static_cast<int>(pixels)
    );

    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_framegen_AiFrameGenerator_nativeRelease(
    JNIEnv*,
    jobject,
    jlong handle
) {
    auto* engine = reinterpret_cast<Engine*>(handle);
    delete engine;
}
