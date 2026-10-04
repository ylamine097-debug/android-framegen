package dev.framegen

import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Pure-GPU (OpenGL ES 3.0) frame generation:
 *   screen capture -> GL texture -> block motion estimation (fragment shader)
 *   -> motion-compensated interpolation of N-1 in-between frames -> overlay surface.
 * Works on any GLES 3.0 GPU, including Mali.
 */
class FrameGenRenderer(
    private val outSurface: Surface,
    private val projection: MediaProjection,
    private val multiplier: Int,
    private var w: Int,
    private var h: Int,
    private val dpi: Int,
    private val refreshHz: Float,
    private val modelDirectory: String,
    private val onBufferSize: (Int, Int) -> Unit
) : Thread("FrameGen-AI") {

    companion object {
        private const val TAG = "FrameGen"
        private const val BLOCK = 16

        private const val VS = """
#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}"""

        private const val FS_OES = """
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
uniform mat4 uSt;
in vec2 vUv;
out vec4 o;
void main() { o = vec4(texture(uTex, (uSt * vec4(vUv, 0.0, 1.0)).xy).rgb, 1.0); }"""

        private const val FS_BLIT = """
#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 o;
void main() { o = vec4(texture(uTex, vUv).rgb, 1.0); }"""

        // One fragment = one 16x16 block. Sparse 4x4 SAD, coarse (+-24px) then fine (+-2px) search.
        private const val FS_ME = """
#version 300 es
precision highp float;
uniform sampler2D uPrev;
uniform sampler2D uCurr;
uniform vec2 uTexel;
in vec2 vUv;
out vec4 o;
float cache[16];
float L(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
float sad(vec2 d) {
    float s = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 p = vUv + (vec2(float(i), float(j)) - 1.5) * 4.0 * uTexel;
            s += abs(cache[j * 4 + i] - L(texture(uPrev, p + d).rgb));
        }
    }
    return s;
}
void main() {
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 p = vUv + (vec2(float(i), float(j)) - 1.5) * 4.0 * uTexel;
            cache[j * 4 + i] = L(texture(uCurr, p).rgb);
        }
    }
    vec2 best = vec2(0.0);
    float bestC = sad(vec2(0.0));
    for (int y = -4; y <= 4; y++) {
        for (int x = -4; x <= 4; x++) {
            if (x == 0 && y == 0) continue;
            vec2 dp = vec2(float(x), float(y)) * 6.0;
            float c = sad(dp * uTexel) + 0.04 * length(vec2(float(x), float(y)));
            if (c < bestC) { bestC = c; best = dp; }
        }
    }
    vec2 center = best;
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            if (x == 0 && y == 0) continue;
            vec2 dp = center + vec2(float(x), float(y));
            float c = sad(dp * uTexel) + 0.04 * length(dp / 6.0);
            if (c < bestC) { bestC = c; best = dp; }
        }
    }
    float err = sad(best * uTexel) / 16.0;
    o = vec4(best / 64.0 + 0.5, clamp(err * 6.0, 0.0, 1.0), 1.0);
}"""

        // curr(x) ~ prev(x + d). Object position at time t: x + d(1-t).
        private const val FS_INTERP = """
#version 300 es
precision highp float;
uniform sampler2D uPrev;
uniform sampler2D uCurr;
uniform sampler2D uMv;
uniform vec2 uTexel;
uniform float uT;
in vec2 vUv;
out vec4 o;

vec3 clampToPairNeighborhood(vec3 value, vec3 a, vec3 b) {
    vec3 lo = min(a, b) - vec3(0.05);
    vec3 hi = max(a, b) + vec3(0.05);
    return clamp(value, lo, hi);
}

void main() {
    vec4 m = texture(uMv, vUv);
    vec2 d = (m.rg - 0.5) * 64.0 * uTexel;

    vec2 prevUv = vUv + d * uT;
    vec2 currUv = vUv - d * (1.0 - uT);

    vec3 a = texture(uPrev, prevUv).rgb;
    vec3 b = texture(uCurr, currUv).rgb;
    vec3 pa = texture(uPrev, vUv).rgb;
    vec3 pb = texture(uCurr, vUv).rgb;

    vec3 comp = mix(a, b, uT);
    comp = clampToPairNeighborhood(comp, pa, pb);

    vec3 plain = mix(pa, pb, uT);
    vec3 nearest = (uT < 0.5) ? pa : pb;
    vec3 fallback = mix(nearest, plain, 0.5);

    float motionConfidence = 1.0 - smoothstep(0.28, 0.78, m.b);
    float warpAgreement = 1.0 - smoothstep(0.08, 0.30, length(a - b));
    float sceneChangeGuard = 1.0 - smoothstep(0.22, 0.62, length(pa - pb));

    float confidence = motionConfidence * warpAgreement * sceneChangeGuard;
    o = vec4(mix(fallback, comp, confidence), 1.0);
}"""
    }

    @Volatile private var running = true
    @Volatile private var pending: IntArray? = null
    private val frameSem = Semaphore(0)

    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surf: EGLSurface = EGL14.EGL_NO_SURFACE

    private var pOes = 0; private var pBlit = 0; private var pMe = 0; private var pInterp = 0
    private var oesTex = 0
    private var st: SurfaceTexture? = null
    private var stSurface: Surface? = null
    private var vd: VirtualDisplay? = null
    private val stMtx = FloatArray(16)

    private val tex = IntArray(2)
    private val fbo = IntArray(2)
    private var mvTex = 0
    private var mvFbo = 0
    private var bw = 0
    private var bh = 0

    // True neural frame-generation path.
    private var aiTex = 0
    private var ai: AiFrameGenerator? = null
    private var previousPixels: ByteBuffer? = null
    private var currentPixels: ByteBuffer? = null
    private var generatedPixels: ByteBuffer? = null
    private var aiReady = false

    fun requestResize(nw: Int, nh: Int) { pending = intArrayOf(nw, nh) }

    fun shutdown() {
        running = false
        interrupt()
        try { join(1500) } catch (_: InterruptedException) {}
    }

    override fun run() {
        try {
            initEgl()
            initGl()
            loop()
        } catch (t: Throwable) {
            Log.e(TAG, "renderer stopped", t)
        } finally {
            cleanup()
        }
    }

    // ---------------------------------------------------------------- main loop
    private fun loop() {
        var cur = 0
        var havePrev = false
        var interval = 33_000_000L
        var lastT = 0L

        while (running) {
            if (applyResize()) havePrev = false
            if (!frameSem.tryAcquire(50, TimeUnit.MILLISECONDS)) continue
            frameSem.drainPermits()

            val s = st ?: break
            s.updateTexImage()
            s.getTransformMatrix(stMtx)
            val now = System.nanoTime()

            drawOes(cur)

            val captureBuffer = currentPixels
            if (captureBuffer == null) {
                ensureAiBuffers()
            }

            captureCurrentFrame()

            if (havePrev) {
                val dt = (now - lastT).coerceIn(8_000_000L, 100_000_000L)
                interval = (interval * 0.7 + dt * 0.3).toLong()

                // Do not ask the compositor for a frame rate the physical panel
                // cannot present.
                val displayLimitedMultiplier = (refreshHz * interval / 1_000_000_000.0)
                    .toInt()
                    .coerceAtLeast(1)
                val effectiveMultiplier = multiplier.coerceAtMost(displayLimitedMultiplier)

                if (effectiveMultiplier <= 1) {
                    drawBlit(tex[cur])
                    EGL14.eglSwapBuffers(dpy, surf)
                } else {
                    val step = interval / effectiveMultiplier
                    val base = System.nanoTime()
                    var generatedAny = false

                    val prevPixels = previousPixels
                    val currPixels = currentPixels
                    val outPixels = generatedPixels
                    val neural = ai

                    if (aiReady && neural != null && prevPixels != null && currPixels != null && outPixels != null) {
                        for (k in 1 until effectiveMultiplier) {
                            val ok = neural.interpolate(
                                prevPixels,
                                currPixels,
                                outPixels,
                                w,
                                h,
                                k.toFloat() / effectiveMultiplier
                            )

                            if (ok) {
                                uploadAiFrame()
                                drawBlit(aiTex)
                                generatedAny = true
                            } else {
                                // Emergency fallback only; this is not labeled as AI.
                                drawInterp(1 - cur, cur, k.toFloat() / effectiveMultiplier)
                            }

                            sleepUntil(base + k * step)
                            EGL14.eglSwapBuffers(dpy, surf)
                        }
                    } else {
                        motion(1 - cur, cur)
                        for (k in 1 until effectiveMultiplier) {
                            drawInterp(1 - cur, cur, k.toFloat() / effectiveMultiplier)
                            sleepUntil(base + k * step)
                            EGL14.eglSwapBuffers(dpy, surf)
                        }
                    }

                    drawBlit(tex[cur])
                    sleepUntil(base + effectiveMultiplier * step)
                    EGL14.eglSwapBuffers(dpy, surf)

                    if (generatedAny) {
                        // Keep the real current frame as the anchor for the next pair.
                    }
                }
            } else {
                drawBlit(tex[cur])
                EGL14.eglSwapBuffers(dpy, surf)
                havePrev = true
            }

            // Swap the CPU AI frame buffers so the next capture becomes the
            // "previous" real frame without allocating every frame.
            val oldPrevious = previousPixels
            previousPixels = currentPixels
            currentPixels = oldPrevious

            lastT = now
            cur = 1 - cur
        }
    }

    private fun sleepUntil(t: Long) {
        while (true) {
            val d = t - System.nanoTime()
            if (d <= 0) return
            LockSupport.parkNanos(d)
        }
    }

    private fun applyResize(): Boolean {
        val p = pending ?: return false
        pending = null
        if (p[0] == w && p[1] == h) return false
        w = p[0]; h = p[1]
        destroyTargets()
        createTargets()
        ensureAiBuffers()
        st?.setDefaultBufferSize(w, h)
        vd?.resize(w, h, dpi)
        onBufferSize(w, h)
        return true
    }

    // ---------------------------------------------------------------- draw passes
    private fun bindTarget(f: Int, vw: Int, vh: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f)
        GLES30.glViewport(0, 0, vw, vh)
    }

    private fun bindTex(unit: Int, t: Int, target: Int = GLES30.GL_TEXTURE_2D) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(target, t)
    }

    private fun loc(p: Int, n: String) = GLES30.glGetUniformLocation(p, n)
    private fun quad() = GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

    private fun drawOes(idx: Int) {
        bindTarget(fbo[idx], w, h)
        GLES30.glUseProgram(pOes)
        bindTex(0, oesTex, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GLES30.glUniform1i(loc(pOes, "uTex"), 0)
        GLES30.glUniformMatrix4fv(loc(pOes, "uSt"), 1, false, stMtx, 0)
        quad()
    }

    private fun motion(prev: Int, curr: Int) {
        bindTarget(mvFbo, bw, bh)
        GLES30.glUseProgram(pMe)
        bindTex(0, tex[prev]); bindTex(1, tex[curr])
        GLES30.glUniform1i(loc(pMe, "uPrev"), 0)
        GLES30.glUniform1i(loc(pMe, "uCurr"), 1)
        GLES30.glUniform2f(loc(pMe, "uTexel"), 1f / w, 1f / h)
        quad()
    }

    private fun drawInterp(prev: Int, curr: Int, t: Float) {
        bindTarget(0, w, h)
        GLES30.glUseProgram(pInterp)
        bindTex(0, tex[prev]); bindTex(1, tex[curr]); bindTex(2, mvTex)
        GLES30.glUniform1i(loc(pInterp, "uPrev"), 0)
        GLES30.glUniform1i(loc(pInterp, "uCurr"), 1)
        GLES30.glUniform1i(loc(pInterp, "uMv"), 2)
        GLES30.glUniform2f(loc(pInterp, "uTexel"), 1f / w, 1f / h)
        GLES30.glUniform1f(loc(pInterp, "uT"), t)
        quad()
    }

    private fun drawBlit(t: Int) {
        bindTarget(0, w, h)
        GLES30.glUseProgram(pBlit)
        bindTex(0, t)
        GLES30.glUniform1i(loc(pBlit, "uTex"), 0)
        quad()
    }

    // ---------------------------------------------------------------- setup
    private fun initEgl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(dpy, v, 0, v, 1)
        val attr = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, attr, 0, cfgs, 0, 1, n, 0)
        ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        surf = EGL14.eglCreateWindowSurface(dpy, cfgs[0], outSurface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        EGL14.eglSwapInterval(dpy, 1)
    }

    private fun initGl() {
        pOes = program(FS_OES); pBlit = program(FS_BLIT); pMe = program(FS_ME); pInterp = program(FS_INTERP)

        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        oesTex = t[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        createTargets()
        ensureAiBuffers()
        ai = try {
            if (modelDirectory.isNotEmpty()) {
                AiFrameGenerator(modelDirectory)
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "AI backend unavailable", t)
            null
        }
        aiReady = ai?.isReady == true
        Log.i(TAG, if (aiReady) "RIFE v4.6 neural frame generation READY" else "RIFE neural backend unavailable; using shader fallback")

        val s = SurfaceTexture(oesTex)
        s.setDefaultBufferSize(w, h)
        s.setOnFrameAvailableListener({ frameSem.release() }, Handler(Looper.getMainLooper()))
        st = s
        val sf = Surface(s)
        stSurface = sf
        vd = projection.createVirtualDisplay(
            "FrameGen", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, sf, null, null
        )
    }

    private fun makeTex(tw: Int, th: Int): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, tw, th, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun makeFbo(t: Int): Int {
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t, 0)
        return f[0]
    }

    private fun createTargets() {
        bw = w / BLOCK; bh = h / BLOCK
        for (i in 0..1) { tex[i] = makeTex(w, h); fbo[i] = makeFbo(tex[i]) }
        mvTex = makeTex(bw, bh); mvFbo = makeFbo(mvTex)
        aiTex = makeTex(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun destroyTargets() {
        GLES30.glDeleteFramebuffers(2, fbo, 0)
        GLES30.glDeleteTextures(2, tex, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(mvFbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(mvTex), 0)
        GLES30.glDeleteTextures(1, intArrayOf(aiTex), 0)
    }

    private fun ensureAiBuffers() {
        val bytes = w.toLong() * h.toLong() * 4L
        if (bytes <= 0L || bytes > Int.MAX_VALUE) return
        val size = bytes.toInt()

        fun ensure(old: ByteBuffer?): ByteBuffer {
            return if (old == null || old.capacity() < size) {
                ByteBuffer.allocateDirect(size)
            } else {
                old
            }
        }

        previousPixels = ensure(previousPixels)
        currentPixels = ensure(currentPixels)
        generatedPixels = ensure(generatedPixels)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
    }

    private fun captureCurrentFrame() {
        val dst = currentPixels ?: return
        dst.position(0)
        // drawOes(cur) leaves the current real frame framebuffer bound.
        GLES30.glReadPixels(
            0,
            0,
            w,
            h,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            dst
        )
        dst.position(0)
    }

    private fun uploadAiFrame() {
        val src = generatedPixels ?: return
        src.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, aiTex)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            0,
            0,
            w,
            h,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            src
        )
        src.position(0)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src.trimIndent())
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader error: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    private fun program(fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, VS))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Link error: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun cleanup() {
        try { vd?.release() } catch (_: Throwable) {}
        try { stSurface?.release() } catch (_: Throwable) {}
        try { st?.release() } catch (_: Throwable) {}
        try { ai?.close() } catch (_: Throwable) {}
        ai = null
        aiReady = false
        previousPixels = null
        currentPixels = null
        generatedPixels = null

        try {
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)
            }
        } catch (_: Throwable) {}
    }
}
