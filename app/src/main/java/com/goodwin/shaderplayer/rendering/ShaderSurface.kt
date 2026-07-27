package com.goodwin.shaderplayer.rendering

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Встраивает GLSurfaceView в Compose и синхронизирует его lifecycle. */
@Composable
fun ShaderSurface(
    controller: ShaderRenderController,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var surface by remember { mutableStateOf<ShaderGLSurfaceView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            ShaderGLSurfaceView(context, controller).also { surface = it }
        },
    )

    LaunchedEffect(controller, surface) {
        val target = surface ?: return@LaunchedEffect
        controller.commands.collect(target::submit)
    }

    DisposableEffect(lifecycleOwner, surface) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> surface?.onResume()
                Lifecycle.Event.ON_PAUSE -> surface?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            surface?.onPause()
        }
    }
}
