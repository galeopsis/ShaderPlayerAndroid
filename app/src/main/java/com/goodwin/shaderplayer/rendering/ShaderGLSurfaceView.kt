package com.goodwin.shaderplayer.rendering

import android.content.Context
import android.os.Build
import android.opengl.GLSurfaceView
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import com.goodwin.shaderplayer.domain.RenderOptimizationSettings

/** GLSurfaceView, который преобразует touch-жесты в управление сценой. */
class ShaderGLSurfaceView(
    context: Context,
    private val controller: ShaderRenderController,
) : GLSurfaceView(context) {
    private val shaderRenderer = ShaderRenderer(controller)
    private var playerControlsEnabled = true
    private var paused = false
    private var optimization = RenderOptimizationSettings()
    private var previousX = 0f
    private var previousY = 0f
    private var previousCentroidX = 0f
    private var previousCentroidY = 0f
    private var frameSchedulerRunning = false
    private var previousChoreographerNanos = 0L
    private var accumulatedFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameSchedulerRunning) return

            if (previousChoreographerNanos == 0L) {
                previousChoreographerNanos = frameTimeNanos
            }
            accumulatedFrameNanos +=
                (frameTimeNanos - previousChoreographerNanos).coerceAtLeast(0L)
            previousChoreographerNanos = frameTimeNanos

            val intervalNanos =
                1_000_000_000L / optimization.targetFps.coerceAtLeast(1)
            if (!paused && accumulatedFrameNanos >= intervalNanos) {
                requestRender()
                accumulatedFrameNanos %= intervalNanos
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = detector.scaleFactor
                queueEvent { shaderRenderer.zoom(scale) }
                requestRender()
                return true
            }
        },
    )

    init {
        setEGLContextClientVersion(3)
        setPreserveEGLContextOnPause(true)
        setRenderer(shaderRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    post(::applyRequestedFrameRate)
                    requestRender()
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    post(::applyRequestedFrameRate)
                    requestRender()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            },
        )
    }

    fun submit(command: RenderCommand) {
        when (command) {
            is RenderCommand.SetPlayerControls -> playerControlsEnabled = command.enabled
            is RenderCommand.SetPaused -> {
                paused = command.paused
                resetFrameSchedulerTiming()
            }
            is RenderCommand.SetOptimization -> {
                optimization = command.settings
                resetFrameSchedulerTiming()
                applyRequestedFrameRate()
            }
            is RenderCommand.LoadShader -> {
                playerControlsEnabled = command.playerControlsEnabled
                optimization = command.optimization
                resetFrameSchedulerTiming()
                applyRequestedFrameRate()
            }
            else -> Unit
        }
        queueEvent { shaderRenderer.enqueue(command) }
        requestRender()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(::applyRequestedFrameRate)
        startFrameScheduler()
    }

    override fun onResume() {
        super.onResume()
        startFrameScheduler()
    }

    override fun onPause() {
        stopFrameScheduler()
        super.onPause()
    }

    override fun onDetachedFromWindow() {
        stopFrameScheduler()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (playerControlsEnabled) scaleDetector.onTouchEvent(event)

        val clicked = event.actionMasked == MotionEvent.ACTION_DOWN
        shaderRenderer.updatePointer(
            x = event.x,
            y = event.y,
            down = event.actionMasked != MotionEvent.ACTION_UP &&
                event.actionMasked != MotionEvent.ACTION_CANCEL,
            clicked = clicked,
        )

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
                previousCentroidX = event.x
                previousCentroidY = event.y
                requestRender()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                updateCentroid(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!playerControlsEnabled) {
                    requestRender()
                    return true
                }

                if (event.pointerCount >= 2) {
                    val centroidX = (event.getX(0) + event.getX(1)) * 0.5f
                    val centroidY = (event.getY(0) + event.getY(1)) * 0.5f
                    val deltaX = centroidX - previousCentroidX
                    val deltaY = centroidY - previousCentroidY
                    queueEvent { shaderRenderer.pan(deltaX, deltaY) }
                    previousCentroidX = centroidX
                    previousCentroidY = centroidY
                } else if (!scaleDetector.isInProgress) {
                    val deltaX = event.x - previousX
                    val deltaY = event.y - previousY
                    queueEvent { shaderRenderer.orbit(deltaX, deltaY) }
                    previousX = event.x
                    previousY = event.y
                }
                requestRender()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                previousX = event.x
                previousY = event.y
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                requestRender()
                return true
            }
        }

        return true
    }

    private fun updateCentroid(event: MotionEvent) {
        if (event.pointerCount < 2) return
        previousCentroidX = (event.getX(0) + event.getX(1)) * 0.5f
        previousCentroidY = (event.getY(0) + event.getY(1)) * 0.5f
    }

    private fun startFrameScheduler() {
        if (frameSchedulerRunning) return
        frameSchedulerRunning = true
        resetFrameSchedulerTiming()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameScheduler() {
        if (!frameSchedulerRunning) return
        frameSchedulerRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        resetFrameSchedulerTiming()
    }

    private fun resetFrameSchedulerTiming() {
        previousChoreographerNanos = 0L
        accumulatedFrameNanos = 0L
    }

    /** Просит compositor подобрать частоту дисплея под выбранный frame pacing. */
    private fun applyRequestedFrameRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !holder.surface.isValid) return
        runCatching {
            val fps = optimization.targetFps.toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                holder.surface.setFrameRate(
                    fps,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else {
                holder.surface.setFrameRate(
                    fps,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                )
            }
        }
    }
}
