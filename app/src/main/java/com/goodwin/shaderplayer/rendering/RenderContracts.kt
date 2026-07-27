package com.goodwin.shaderplayer.rendering

import android.graphics.Bitmap
import com.goodwin.shaderplayer.domain.RenderOptimizationSettings
import com.goodwin.shaderplayer.domain.RendererStats
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** Команды, которые должны быть исполнены строго в OpenGL-потоке. */
sealed interface RenderCommand {
    data class LoadShader(
        val source: String,
        val displayName: String,
        val playerControlsEnabled: Boolean,
        val optimization: RenderOptimizationSettings,
        val requestId: Long? = null,
    ) : RenderCommand

    data class SetPaused(val paused: Boolean) : RenderCommand
    data class SetPlayerControls(val enabled: Boolean) : RenderCommand
    data class SetOptimization(val settings: RenderOptimizationSettings) : RenderCommand
    data class SetTexture(val channel: Int, val bitmap: Bitmap) : RenderCommand
    data class ClearTexture(val channel: Int) : RenderCommand
    data class SetSphereOffset(val value: Float) : RenderCommand
    data object ResetCamera : RenderCommand
    data object ResetTime : RenderCommand
}

/** Результат компиляции документа, отправленного из ViewModel. */
sealed interface ShaderLoadResult {
    val requestId: Long

    data class Success(
        override val requestId: Long,
        val usesSphereOffset: Boolean,
    ) : ShaderLoadResult

    data class Failure(
        override val requestId: Long,
    ) : ShaderLoadResult
}

/** Связывает Compose/ViewModel с SurfaceView без прямой зависимости UI от GLES. */
class ShaderRenderController {
    private val _commands = Channel<RenderCommand>(Channel.UNLIMITED)
    val commands: Flow<RenderCommand> = _commands.receiveAsFlow()

    private val _shaderLoadResults = Channel<ShaderLoadResult>(Channel.UNLIMITED)
    val shaderLoadResults: Flow<ShaderLoadResult> = _shaderLoadResults.receiveAsFlow()

    private val _stats = MutableStateFlow(RendererStats())
    val stats: StateFlow<RendererStats> = _stats.asStateFlow()

    private val _compileError = MutableStateFlow<String?>(null)
    val compileError: StateFlow<String?> = _compileError.asStateFlow()

    fun submit(command: RenderCommand) {
        _commands.trySend(command)
    }

    internal fun publishStats(stats: RendererStats) {
        _stats.value = stats
    }

    internal fun publishCompileError(error: String?) {
        _compileError.value = error
    }

    internal fun publishShaderLoadResult(result: ShaderLoadResult) {
        _shaderLoadResults.trySend(result)
    }
}
