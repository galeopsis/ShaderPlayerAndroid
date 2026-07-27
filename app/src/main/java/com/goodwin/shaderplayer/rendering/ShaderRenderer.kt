package com.goodwin.shaderplayer.rendering

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.goodwin.shaderplayer.domain.RendererStats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Calendar
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/** Полноэкранный OpenGL ES 3.0 renderer для ShaderToy-подобных fragment shader. */
class ShaderRenderer(
    private val controller: ShaderRenderController,
) : GLSurfaceView.Renderer {
    private data class TextureSlot(
        var id: Int = 0,
        var width: Int = 1,
        var height: Int = 1,
    )

    private val adapter = ShaderSourceAdapter()
    private val commands = ConcurrentLinkedQueue<RenderCommand>()
    private val textures = Array(4) { TextureSlot() }

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var program = 0
    private var vertexArray = 0
    private var vertexBuffer = 0

    private var startedAtNanos = System.nanoTime()
    private var pauseStartedAtNanos = 0L
    private var pausedDurationNanos = 0L
    private var previousFrameNanos = startedAtNanos
    private var frameIndex = 0
    private var paused = false

    private var statsWindowStartedNanos = startedAtNanos
    private var statsFrames = 0

    private var currentSource = ""
    private var currentName = ""
    private var playerControlsEnabled = true
    private var usesSphereOffset = false
    private var sphereOffset = 0f

    private var yaw = 0f
    private var pitch = 0f
    private var panX = 0f
    private var panY = 0f
    private var zoom = 1f

    @Volatile private var pointerX = 0f
    @Volatile private var pointerY = 0f
    @Volatile private var pointerDown = false
    @Volatile private var clickX = 0f
    @Volatile private var clickY = 0f

    private val fullscreenVertices: FloatBuffer = ByteBuffer
        .allocateDirect(6 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f))
            position(0)
        }

    fun enqueue(command: RenderCommand) {
        commands.add(command)
    }

    fun updatePointer(x: Float, y: Float, down: Boolean, clicked: Boolean) {
        pointerX = x
        pointerY = y
        pointerDown = down
        if (clicked) {
            clickX = x
            clickY = y
        }
    }

    fun orbit(deltaX: Float, deltaY: Float) {
        if (!playerControlsEnabled) return
        yaw += deltaX * 0.006f
        pitch = (pitch + deltaY * 0.006f).coerceIn(-1.35f, 1.35f)
    }

    fun pan(deltaX: Float, deltaY: Float) {
        if (!playerControlsEnabled) return
        val scale = 1f / max(viewportHeight, 1)
        panX += deltaX * scale * zoom
        panY -= deltaY * scale * zoom
    }

    fun zoom(scaleFactor: Float) {
        if (!playerControlsEnabled || !scaleFactor.isFinite()) return
        zoom = (zoom / scaleFactor).coerceIn(0.08f, 12f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        createFullscreenGeometry()
        createFallbackTextures()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = max(width, 1)
        viewportHeight = max(height, 1)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        drainCommands()
        GLES30.glClearColor(0.015f, 0.02f, 0.03f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (program == 0) return

        val now = System.nanoTime()
        val elapsed = effectiveElapsedSeconds(now)
        val delta = if (paused) 0f else ((now - previousFrameNanos) / 1_000_000_000.0).toFloat()
        previousFrameNanos = now

        GLES30.glUseProgram(program)
        bindFrameUniforms(elapsed, delta)
        bindTextures()
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glUseProgram(0)

        frameIndex++
        updateStats(now)
    }

    private fun drainCommands() {
        while (true) {
            when (val command = commands.poll() ?: break) {
                is RenderCommand.LoadShader -> loadShader(command)
                is RenderCommand.SetPaused -> setPaused(command.paused)
                is RenderCommand.SetPlayerControls -> setPlayerControls(command.enabled)
                is RenderCommand.SetTexture -> uploadTexture(command.channel, command.bitmap)
                is RenderCommand.ClearTexture -> resetTexture(command.channel)
                is RenderCommand.SetSphereOffset -> sphereOffset = command.value
                RenderCommand.ResetCamera -> resetCamera()
                RenderCommand.ResetTime -> resetTime()
            }
        }
    }

    private fun loadShader(command: RenderCommand.LoadShader) {
        val adapted = adapter.adapt(command.source, command.playerControlsEnabled)
        val newProgram = try {
            createProgram(VERTEX_SHADER, adapted.source)
        } catch (error: ShaderCompileException) {
            controller.publishCompileError(
                buildString {
                    appendLine(command.displayName)
                    appendLine(error.message.orEmpty())
                },
            )
            command.requestId?.let { requestId ->
                controller.publishShaderLoadResult(ShaderLoadResult.Failure(requestId))
            }
            return
        }

        if (program != 0) GLES30.glDeleteProgram(program)
        program = newProgram
        currentSource = command.source
        currentName = command.displayName
        playerControlsEnabled = command.playerControlsEnabled
        usesSphereOffset = adapted.usesSphereOffset
        sphereOffset = 0f
        resetCamera()
        controller.publishCompileError(null)
        resetTime()
        command.requestId?.let { requestId ->
            controller.publishShaderLoadResult(
                ShaderLoadResult.Success(
                    requestId = requestId,
                    usesSphereOffset = adapted.usesSphereOffset,
                ),
            )
        }
    }

    private fun setPlayerControls(enabled: Boolean) {
        if (playerControlsEnabled == enabled) return
        playerControlsEnabled = enabled
        if (currentSource.isNotBlank()) {
            loadShader(
                RenderCommand.LoadShader(
                    source = currentSource,
                    displayName = currentName,
                    playerControlsEnabled = enabled,
                ),
            )
        }
    }

    private fun setPaused(value: Boolean) {
        if (paused == value) return
        val now = System.nanoTime()
        if (value) {
            pauseStartedAtNanos = now
        } else if (pauseStartedAtNanos != 0L) {
            pausedDurationNanos += now - pauseStartedAtNanos
            pauseStartedAtNanos = 0L
        }
        paused = value
    }

    private fun resetTime() {
        startedAtNanos = System.nanoTime()
        previousFrameNanos = startedAtNanos
        pausedDurationNanos = 0L
        pauseStartedAtNanos = if (paused) startedAtNanos else 0L
        frameIndex = 0
    }

    private fun resetCamera() {
        yaw = 0f
        pitch = 0f
        panX = 0f
        panY = 0f
        zoom = 1f
    }

    private fun effectiveElapsedSeconds(now: Long): Float {
        val activePause = if (paused && pauseStartedAtNanos != 0L) {
            now - pauseStartedAtNanos
        } else {
            0L
        }
        return ((now - startedAtNanos - pausedDurationNanos - activePause) / 1_000_000_000.0).toFloat()
    }

    private fun bindFrameUniforms(elapsed: Float, delta: Float) {
        val width = viewportWidth.toFloat()
        val height = viewportHeight.toFloat()
        val frameRate = if (delta > 0f) 1f / delta else 0f
        val mouseY = height - pointerY
        val clickMouseY = height - clickY
        val mouseZ = if (pointerDown) clickX else -kotlin.math.abs(clickX)
        val mouseW = if (pointerDown) clickMouseY else -kotlin.math.abs(clickMouseY)

        uniform2f("spViewportSize", width, height)
        uniform3f("iResolution", width, height, 1f)
        uniform1f("iTime", elapsed)
        uniform1f("iTimeDelta", delta)
        uniform1f("iFrameRate", frameRate)
        uniform1i("iFrame", frameIndex)
        uniform1f("iSampleRate", 44_100f)

        // Стандартные uniform-переменные Bonzomatic и их распространённые aliases.
        uniform1f("fGlobalTime", elapsed)
        uniform1f("fFrameTime", delta)
        uniform1f("fFrameRate", frameRate)
        uniform2f("v2Resolution", width, height)
        uniform2f("resolution", width, height)
        uniform2f("uResolution", width, height)
        uniform1f("time", elapsed)
        uniform1f("uTime", elapsed)

        uniform4f("iMouse", pointerX, mouseY, mouseZ, mouseW)
        uniform4f("v4Mouse", pointerX, mouseY, mouseZ, mouseW)
        uniform2f("v2Mouse", pointerX, mouseY)
        uniform2f("mouse", pointerX / width, mouseY / height)
        uniform2f("uMouse", pointerX / width, mouseY / height)

        val calendar = Calendar.getInstance()
        val seconds = calendar.get(Calendar.HOUR_OF_DAY) * 3600f +
            calendar.get(Calendar.MINUTE) * 60f +
            calendar.get(Calendar.SECOND) +
            calendar.get(Calendar.MILLISECOND) / 1000f
        uniform4f(
            "iDate",
            calendar.get(Calendar.YEAR).toFloat(),
            (calendar.get(Calendar.MONTH) + 1).toFloat(),
            calendar.get(Calendar.DAY_OF_MONTH).toFloat(),
            seconds,
        )

        uniform2f("spSceneRotation", yaw, pitch)
        uniform2f("spScenePan", panX, panY)
        uniform1f("spSceneZoom", zoom)
        if (usesSphereOffset) uniform1f("iSphereOffset", sphereOffset)

        val channelResolutionLocation = GLES30.glGetUniformLocation(program, "iChannelResolution[0]")
        if (channelResolutionLocation >= 0) {
            val values = FloatArray(12)
            textures.forEachIndexed { index, slot ->
                values[index * 3] = slot.width.toFloat()
                values[index * 3 + 1] = slot.height.toFloat()
                values[index * 3 + 2] = 1f
            }
            GLES30.glUniform3fv(channelResolutionLocation, 4, values, 0)
        }

        val channelTimeLocation = GLES30.glGetUniformLocation(program, "iChannelTime[0]")
        if (channelTimeLocation >= 0) {
            GLES30.glUniform1fv(channelTimeLocation, 4, floatArrayOf(elapsed, elapsed, elapsed, elapsed), 0)
        }
    }

    private fun bindTextures() {
        textures.forEachIndexed { index, slot ->
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + index)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, slot.id)
            uniform1i("iChannel$index", index)
        }

        // Стандартные имена текстур Bonzomatic. Одномерные FFT-текстуры
        // эмулируются обычными GL_TEXTURE_2D с выборкой по строке y = 0.5.
        uniform1i("texFFT", 0)
        uniform1i("texFFTSmoothed", 1)
        uniform1i("texFFTIntegrated", 2)
        uniform1i("texPreviousFrame", 3)
        uniform1i("texChecker", 0)
        uniform1i("texNoise", 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun createFallbackTextures() {
        textures.forEachIndexed { index, _ -> resetTexture(index) }
    }

    private fun resetTexture(channel: Int) {
        if (channel !in textures.indices) return
        val slot = textures[channel]
        if (slot.id != 0) GLES30.glDeleteTextures(1, intArrayOf(slot.id), 0)

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        slot.id = ids[0]
        slot.width = 1
        slot.height = 1

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, slot.id)
        setTextureParameters()
        val black = ByteBuffer.allocateDirect(4).put(byteArrayOf(0, 0, 0, -1)).apply { position(0) }
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            1,
            1,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            black,
        )
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun uploadTexture(channel: Int, bitmap: Bitmap) {
        if (channel !in textures.indices) {
            bitmap.recycle()
            return
        }

        val slot = textures[channel]
        if (slot.id != 0) GLES30.glDeleteTextures(1, intArrayOf(slot.id), 0)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        slot.id = ids[0]
        slot.width = bitmap.width
        slot.height = bitmap.height

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, slot.id)
        setTextureParameters()
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        bitmap.recycle()
    }

    private fun setTextureParameters() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
    }

    private fun createFullscreenGeometry() {
        val arrays = IntArray(1)
        val buffers = IntArray(1)
        GLES30.glGenVertexArrays(1, arrays, 0)
        GLES30.glGenBuffers(1, buffers, 0)
        vertexArray = arrays[0]
        vertexBuffer = buffers[0]

        GLES30.glBindVertexArray(vertexArray)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            fullscreenVertices.capacity() * Float.SIZE_BYTES,
            fullscreenVertices,
            GLES30.GL_STATIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val linked = GLES30.glCreateProgram()
        GLES30.glAttachShader(linked, vertex)
        GLES30.glAttachShader(linked, fragment)
        GLES30.glLinkProgram(linked)

        val status = IntArray(1)
        GLES30.glGetProgramiv(linked, GLES30.GL_LINK_STATUS, status, 0)
        val log = GLES30.glGetProgramInfoLog(linked).orEmpty()
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)

        if (status[0] == 0) {
            GLES30.glDeleteProgram(linked)
            throw ShaderCompileException("Program link failed:\n$log")
        }
        return linked
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader).orEmpty()
            GLES30.glDeleteShader(shader)
            throw ShaderCompileException(log)
        }
        return shader
    }

    private fun updateStats(now: Long) {
        statsFrames++
        val windowNanos = now - statsWindowStartedNanos
        if (windowNanos < 500_000_000L) return

        val seconds = windowNanos / 1_000_000_000.0
        val fps = (statsFrames / seconds).toFloat()
        controller.publishStats(
            RendererStats(
                fps = fps,
                frameTimeMs = if (fps > 0f) 1000f / fps else 0f,
            ),
        )
        statsWindowStartedNanos = now
        statsFrames = 0
    }

    private fun uniform1f(name: String, x: Float) {
        val location = GLES30.glGetUniformLocation(program, name)
        if (location >= 0) GLES30.glUniform1f(location, x)
    }

    private fun uniform1i(name: String, x: Int) {
        val location = GLES30.glGetUniformLocation(program, name)
        if (location >= 0) GLES30.glUniform1i(location, x)
    }

    private fun uniform2f(name: String, x: Float, y: Float) {
        val location = GLES30.glGetUniformLocation(program, name)
        if (location >= 0) GLES30.glUniform2f(location, x, y)
    }

    private fun uniform3f(name: String, x: Float, y: Float, z: Float) {
        val location = GLES30.glGetUniformLocation(program, name)
        if (location >= 0) GLES30.glUniform3f(location, x, y, z)
    }

    private fun uniform4f(name: String, x: Float, y: Float, z: Float, w: Float) {
        val location = GLES30.glGetUniformLocation(program, name)
        if (location >= 0) GLES30.glUniform4f(location, x, y, z, w)
    }

    private class ShaderCompileException(message: String) : RuntimeException(message)

    private companion object {
        const val VERTEX_SHADER = """
#version 300 es
layout(location = 0) in vec2 aPosition;
uniform vec2 spViewportSize;
out vec2 fragCoord;
void main()
{
    gl_Position = vec4(aPosition, 0.0, 1.0);
    fragCoord = (aPosition * 0.5 + 0.5) * spViewportSize;
}
"""
    }
}
