package com.goodwin.shaderplayer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goodwin.shaderplayer.R
import com.goodwin.shaderplayer.data.SettingsRepository
import com.goodwin.shaderplayer.data.ShaderRepository
import com.goodwin.shaderplayer.domain.PlayerSettings
import com.goodwin.shaderplayer.domain.ShaderDocument
import com.goodwin.shaderplayer.domain.TextureChannelState
import com.goodwin.shaderplayer.rendering.RenderCommand
import com.goodwin.shaderplayer.rendering.ShaderLoadResult
import com.goodwin.shaderplayer.rendering.ShaderRenderController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Полное состояние экрана плеера. */
data class ShaderPlayerUiState(
    val document: ShaderDocument? = null,
    val paused: Boolean = false,
    val loading: Boolean = false,
    val userMessage: String? = null,
    val textures: List<TextureChannelState> = List(4) { TextureChannelState() },
    val usesSphereOffset: Boolean = false,
    val sphereOffset: Float = 0f,
)

/** Координирует SAF, корутины, настройки и OpenGL-команды. */
class ShaderPlayerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private data class PendingDocument(
        val requestId: Long,
        val document: ShaderDocument,
        val fallbackToBuiltInOnFailure: Boolean,
    )

    private val shaderRepository = ShaderRepository(application)
    private val settingsRepository = SettingsRepository(application)

    val renderController = ShaderRenderController()

    private val _uiState = MutableStateFlow(ShaderPlayerUiState())
    val uiState: StateFlow<ShaderPlayerUiState> = _uiState.asStateFlow()

    private var documentLoadJob: Job? = null
    private var pendingDocument: PendingDocument? = null
    private var nextRequestId = 1L

    val settings: StateFlow<PlayerSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerSettings(),
    )

    init {
        observeShaderLoadResults()
        restoreLastShaderOrBuiltIn()
    }

    fun openShader(uri: Uri) {
        documentLoadJob?.cancel()
        renderController.publishCompileError(null)
        documentLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, userMessage = null) }
            runCatching {
                val document = shaderRepository.readShader(uri)
                val controlsEnabled = settingsRepository.settings.first().playerControlsEnabled
                document to controlsEnabled
            }.onSuccess { (document, controlsEnabled) ->
                submitDocument(
                    document = document,
                    controlsEnabled = controlsEnabled,
                    fallbackToBuiltInOnFailure = false,
                )
            }.onFailure {
                _uiState.update {
                    it.copy(
                        loading = false,
                        userMessage = getApplication<Application>().getString(R.string.shader_read_error),
                    )
                }
            }
        }
    }

    fun reloadShader() {
        val uri = _uiState.value.document?.uri
        if (uri == null) {
            loadBuiltInShader()
        } else {
            openShader(uri)
        }
    }

    fun selectTexture(channel: Int, uri: Uri) {
        if (channel !in 0..3) return
        viewModelScope.launch {
            runCatching { shaderRepository.readTexture(uri) }
                .onSuccess { (name, bitmap) ->
                    val updated = _uiState.value.textures.toMutableList()
                    updated[channel] = TextureChannelState(name, uri)
                    _uiState.update { it.copy(textures = updated) }
                    renderController.submit(RenderCommand.SetTexture(channel, bitmap))
                }
                .onFailure {
                    _uiState.update {
                        it.copy(userMessage = getApplication<Application>().getString(R.string.texture_read_error))
                    }
                }
        }
    }

    fun clearTexture(channel: Int) {
        if (channel !in 0..3) return
        val updated = _uiState.value.textures.toMutableList()
        updated[channel] = TextureChannelState()
        _uiState.update { it.copy(textures = updated) }
        renderController.submit(RenderCommand.ClearTexture(channel))
    }

    fun togglePause() {
        val paused = !_uiState.value.paused
        _uiState.update { it.copy(paused = paused) }
        renderController.submit(RenderCommand.SetPaused(paused))
    }

    fun resetCamera() {
        renderController.submit(RenderCommand.ResetCamera)
    }

    fun resetTime() {
        renderController.submit(RenderCommand.ResetTime)
    }

    fun setPlayerControlsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPlayerControlsEnabled(enabled)
            renderController.submit(RenderCommand.SetPlayerControls(enabled))
        }
    }

    fun setShowStats(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowStats(enabled)
        }
    }

    fun setSphereOffset(value: Float) {
        _uiState.update { it.copy(sphereOffset = value) }
        renderController.submit(RenderCommand.SetSphereOffset(value))
    }

    fun consumeMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    private fun observeShaderLoadResults() {
        viewModelScope.launch {
            renderController.shaderLoadResults.collect { result ->
                val pending = pendingDocument
                if (pending == null || pending.requestId != result.requestId) {
                    return@collect
                }

                when (result) {
                    is ShaderLoadResult.Success -> {
                        pendingDocument = null
                        _uiState.update {
                            it.copy(
                                document = pending.document,
                                loading = false,
                                userMessage = null,
                                usesSphereOffset = result.usesSphereOffset,
                                sphereOffset = 0f,
                            )
                        }
                        pending.document.uri?.let { uri ->
                            settingsRepository.setLastSuccessfulShaderUri(uri.toString())
                        }
                    }

                    is ShaderLoadResult.Failure -> {
                        pendingDocument = null
                        _uiState.update { it.copy(loading = false) }

                        if (pending.fallbackToBuiltInOnFailure) {
                            settingsRepository.setLastSuccessfulShaderUri(null)
                            renderController.publishCompileError(null)
                            loadBuiltInShader()
                        }
                    }
                }
            }
        }
    }

    /** При старте восстанавливает только документ, который ранее успешно скомпилировался. */
    private fun restoreLastShaderOrBuiltIn() {
        documentLoadJob?.cancel()
        documentLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, userMessage = null) }
            val controlsEnabled = settingsRepository.settings.first().playerControlsEnabled
            val storedUri = settingsRepository.lastSuccessfulShaderUri.first()

            if (storedUri.isNullOrBlank()) {
                submitDocument(
                    document = shaderRepository.readBuiltInShader(),
                    controlsEnabled = controlsEnabled,
                    fallbackToBuiltInOnFailure = false,
                )
                return@launch
            }

            val restoredDocument = runCatching {
                shaderRepository.readShader(Uri.parse(storedUri))
            }.getOrNull()

            if (restoredDocument == null) {
                settingsRepository.setLastSuccessfulShaderUri(null)
                submitDocument(
                    document = shaderRepository.readBuiltInShader(),
                    controlsEnabled = controlsEnabled,
                    fallbackToBuiltInOnFailure = false,
                )
            } else {
                submitDocument(
                    document = restoredDocument,
                    controlsEnabled = controlsEnabled,
                    fallbackToBuiltInOnFailure = true,
                )
            }
        }
    }

    private fun loadBuiltInShader() {
        documentLoadJob?.cancel()
        documentLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, userMessage = null) }
            val controlsEnabled = settingsRepository.settings.first().playerControlsEnabled
            submitDocument(
                document = shaderRepository.readBuiltInShader(),
                controlsEnabled = controlsEnabled,
                fallbackToBuiltInOnFailure = false,
            )
        }
    }

    private fun submitDocument(
        document: ShaderDocument,
        controlsEnabled: Boolean,
        fallbackToBuiltInOnFailure: Boolean,
    ) {
        val requestId = nextRequestId++
        pendingDocument = PendingDocument(
            requestId = requestId,
            document = document,
            fallbackToBuiltInOnFailure = fallbackToBuiltInOnFailure,
        )
        renderController.submit(
            RenderCommand.LoadShader(
                source = document.source,
                displayName = document.name,
                playerControlsEnabled = controlsEnabled,
                requestId = requestId,
            ),
        )
    }
}
