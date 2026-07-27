package com.goodwin.shaderplayer.rendering

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/** GLSurfaceView, который преобразует touch-жесты в управление сценой. */
class ShaderGLSurfaceView(
    context: Context,
    private val controller: ShaderRenderController,
) : GLSurfaceView(context) {
    private val shaderRenderer = ShaderRenderer(controller)
    private var playerControlsEnabled = true
    private var previousX = 0f
    private var previousY = 0f
    private var previousCentroidX = 0f
    private var previousCentroidY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = detector.scaleFactor
                queueEvent { shaderRenderer.zoom(scale) }
                return true
            }
        },
    )

    init {
        setEGLContextClientVersion(3)
        setPreserveEGLContextOnPause(true)
        setRenderer(shaderRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun submit(command: RenderCommand) {
        when (command) {
            is RenderCommand.SetPlayerControls -> playerControlsEnabled = command.enabled
            is RenderCommand.LoadShader -> playerControlsEnabled = command.playerControlsEnabled
            else -> Unit
        }
        queueEvent { shaderRenderer.enqueue(command) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (playerControlsEnabled) {
            scaleDetector.onTouchEvent(event)
        }

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
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                updateCentroid(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!playerControlsEnabled) return true

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
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                previousX = event.x
                previousY = event.y
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> return true
        }

        return true
    }

    private fun updateCentroid(event: MotionEvent) {
        if (event.pointerCount < 2) return
        previousCentroidX = (event.getX(0) + event.getX(1)) * 0.5f
        previousCentroidY = (event.getY(0) + event.getY(1)) * 0.5f
    }
}
