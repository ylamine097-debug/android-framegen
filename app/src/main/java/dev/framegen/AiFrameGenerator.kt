package dev.framegen

import java.nio.ByteBuffer

/**
 * JNI bridge to the bundled RIFE v4.6 neural frame interpolation backend.
 *
 * Inputs/outputs are tightly packed RGBA8 buffers at the processing resolution.
 * The native side converts to RGB, runs RIFE on Vulkan through ncnn, and writes
 * the generated RGBA frame into the output buffer.
 */
class AiFrameGenerator(
    modelDirectory: String,
    gpuIndex: Int = -2
) : AutoCloseable {

    companion object {
        init {
            System.loadLibrary("framegen_ai")
        }
    }

    private var handle: Long = nativeCreate(modelDirectory, gpuIndex)

    val isReady: Boolean
        get() = handle != 0L

    fun interpolate(
        previous: ByteBuffer,
        current: ByteBuffer,
        output: ByteBuffer,
        width: Int,
        height: Int,
        timestep: Float
    ): Boolean {
        val h = handle
        if (h == 0L) return false
        return nativeInterpolate(
            h, previous, current, output, width, height, timestep.coerceIn(0.01f, 0.99f)
        ) == 0
    }

    override fun close() {
        val h = handle
        if (h != 0L) {
            nativeRelease(h)
            handle = 0L
        }
    }

    private external fun nativeCreate(modelDirectory: String, gpuIndex: Int): Long

    private external fun nativeInterpolate(
        handle: Long,
        previous: ByteBuffer,
        current: ByteBuffer,
        output: ByteBuffer,
        width: Int,
        height: Int,
        timestep: Float
    ): Int

    private external fun nativeRelease(handle: Long)
}
