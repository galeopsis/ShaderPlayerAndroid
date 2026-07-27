package com.goodwin.shaderplayer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goodwin.shaderplayer.R
import com.goodwin.shaderplayer.data.GraphicsBackendManager
import com.goodwin.shaderplayer.data.SettingsRepository
import com.goodwin.shaderplayer.data.ShaderRepository
import com.goodwin.shaderplayer.domain.PlayerSettings
import com.goodwin.shaderplayer.domain.RenderBackend
import com.goodwin.shaderplayer.domain.RenderOptimizationSettings
import com.goodwin.shaderplayer.domain.ShaderDocument
import com.goodwin.shaderplayer.domain.ShaderPrecision
import com.goodwin.shaderplayer.domain.ShaderQualityPreset
import com.goodwin.shaderplayer.domain.TextureChannelState
import com.goodwin.shaderplayer.domain.UpscaleFilter
import com.goodwin.shaderplayer.rendering.RenderCommand
import com.goodwin.shaderplayer.rendering.ShaderLoadResult
import com.goodwin.shaderplayer.rendering.ShaderRenderController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Быстрые наборы параметров для типовых сценариев. */
enum class OptimizationProfile {
    ORIGINAL,
    BALANCED,
    PERFORMANCE,
}

/** Полное состояние экрана плеера. */
data class ShaderPlayerUiState(
    val document: ShaderDocument? = null,
    val paused: Boolean = false,
    val loading: Boolean = false,
    val userMessage: String? = null,
    val textures: List<TextureChannelState> = List(4) { TextureChannelState() },
    val usesSphereOffset: Boolean = false,
    val sphereOffset: Float = 0f,
    val vulkanAvailable: Boolean = false,
)

/** Координирует SAF, корутины, настройки и команды renderer'а. */
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
    private val graphicsBackendManager = GraphicsBackendManager(application)

    val renderController = ShaderRenderController()

    private val _uiState = MutableStateFlow(
        ShaderPlayerUiState(vulkanAvailable = graphicsBackendManager.vulkanAvailable),
    )
    val uiState: StateFlow<ShaderPlayerUiState> = _uiState.asStateFlow()

    private val _restartRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val restartRequests: SharedFlow<Unit> = _restartRequests.asSharedFlow()

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
                val playerSettings = settingsRepository.settings.first()
                document to playerSettings
            }.onSuccess { (document, playerSettings) ->
                submitDocument(
                    document = document,
                    playerSettings = playerSettings,
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
        if (uri == null) loadBuiltInShader() else openShader(uri)
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
        viewModelScope.launch { settingsRepository.setShowStats(enabled) }
    }

    fun setRenderBackend(backend: RenderBackend) {
        viewModelScope.launch {
            when (val result = graphicsBackendManager.applyBackend(backend)) {
                GraphicsBackendManager.ApplyResult.Applied -> {
                    settingsRepository.setRenderBackend(backend)
                    _restartRequests.tryEmit(Unit)
                }

                is GraphicsBackendManager.ApplyResult.PermissionRequired -> {
                    _uiState.update {
                        it.copy(
                            userMessage = getApplication<Application>().getString(
                                R.string.angle_permission_required,
                                result.adbCommand,
                            ),
                        )
                    }
                }

                GraphicsBackendManager.ApplyResult.Unsupported -> {
                    _uiState.update {
                        it.copy(userMessage = getApplication<Application>().getString(R.string.vulkan_unavailable))
                    }
                }

                is GraphicsBackendManager.ApplyResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            userMessage = getApplication<Application>().getString(
                                R.string.backend_apply_failed,
                                result.reason,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun setRenderScale(value: Float) = updateOptimization { copy(renderScale = value) }

    fun setDynamicResolutionEnabled(enabled: Boolean) =
        updateOptimization { copy(dynamicResolutionEnabled = enabled) }

    fun setMinimumRenderScale(value: Float) =
        updateOptimization { copy(minimumRenderScale = value) }

    fun setTargetFps(value: Int) = updateOptimization { copy(targetFps = value) }

    fun setQualityPreset(value: ShaderQualityPreset) =
        updateOptimization { copy(qualityPreset = value) }

    fun setShaderPrecision(value: ShaderPrecision) =
        updateOptimization { copy(precision = value) }

    fun setUpscaleFilter(value: UpscaleFilter) =
        updateOptimization { copy(upscaleFilter = value) }

    fun applyOptimizationProfile(profile: OptimizationProfile) {
        viewModelScope.launch {
            val optimized = when (profile) {
                OptimizationProfile.ORIGINAL -> RenderOptimizationSettings()
                OptimizationProfile.BALANCED -> RenderOptimizationSettings(
                    renderScale = 0.75f,
                    dynamicResolutionEnabled = true,
                    minimumRenderScale = 0.55f,
                    targetFps = 60,
                    qualityPreset = ShaderQualityPreset.BALANCED,
                    precision = ShaderPrecision.HIGH,
                    upscaleFilter = UpscaleFilter.LINEAR,
                )
                OptimizationProfile.PERFORMANCE -> RenderOptimizationSettings(
                    renderScale = 0.67f,
                    dynamicResolutionEnabled = true,
                    minimumRenderScale = 0.50f,
                    targetFps = 60,
                    qualityPreset = ShaderQualityPreset.PERFORMANCE,
                    precision = ShaderPrecision.MEDIUM,
                    upscaleFilter = UpscaleFilter.LINEAR,
                )
            }
            settingsRepository.applyOptimizationPreset(optimized)
            renderController.submit(RenderCommand.SetOptimization(optimized))
        }
    }

    fun setSphereOffset(value: Float) {
        _uiState.update { it.copy(sphereOffset = value) }
        renderController.submit(RenderCommand.SetSphereOffset(value))
    }

    fun consumeMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    private fun updateOptimization(
        transform: RenderOptimizationSettings.() -> RenderOptimizationSettings,
    ) {
        viewModelScope.launch {
            val current = settingsRepository.settings.first().optimization
            val updated = current.transform().let { value ->
                value.copy(
                    renderScale = value.renderScale.coerceIn(0.50f, 1.0f),
                    minimumRenderScale = value.minimumRenderScale
                        .coerceIn(0.50f, value.renderScale.coerceIn(0.50f, 1.0f)),
                )
            }
            settingsRepository.applyOptimizationPreset(updated)
            renderController.submit(RenderCommand.SetOptimization(updated))
        }
    }

    private fun observeShaderLoadResults() {
        viewModelScope.launch {
            renderController.shaderLoadResults.collect { result ->
                val pending = pendingDocument
                if (pending == null || pending.requestId != result.requestId) return@collect

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
            val playerSettings = settingsRepository.settings.first()
            val storedUri = settingsRepository.lastSuccessfulShaderUri.first()

            if (storedUri.isNullOrBlank()) {
                submitDocument(
                    document = shaderRepository.readBuiltInShader(),
                    playerSettings = playerSettings,
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
                    playerSettings = playerSettings,
                    fallbackToBuiltInOnFailure = false,
                )
            } else {
                submitDocument(
                    document = restoredDocument,
                    playerSettings = playerSettings,
                    fallbackToBuiltInOnFailure = true,
                )
            }
        }
    }

    private fun loadBuiltInShader() {
        documentLoadJob?.cancel()
        documentLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, userMessage = null) }
            submitDocument(
                document = shaderRepository.readBuiltInShader(),
                playerSettings = settingsRepository.settings.first(),
                fallbackToBuiltInOnFailure = false,
            )
        }
    }

    private fun submitDocument(
        document: ShaderDocument,
        playerSettings: PlayerSettings,
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
                playerControlsEnabled = playerSettings.playerControlsEnabled,
                optimization = playerSettings.optimization,
                requestId = requestId,
            ),
        )
    }
}
