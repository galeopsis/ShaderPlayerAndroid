package com.goodwin.shaderplayer.domain

import android.net.Uri

/** Открытый пользователем текстовый шейдер. */
data class ShaderDocument(
    val name: String,
    val source: String,
    val uri: Uri? = null,
)

/** Фактически выбираемый графический backend Android-версии. */
enum class RenderBackend {
    OPENGL_ES,
    VULKAN_ANGLE,
}

/** Уровень вмешательства адаптера в известные константы качества шейдера. */
enum class ShaderQualityPreset {
    ORIGINAL,
    BALANCED,
    PERFORMANCE,
}

/** Точность float по умолчанию в сгенерированном GLSL ES. */
enum class ShaderPrecision {
    HIGH,
    MEDIUM,
}

/** Фильтр растяжения внутреннего render target на экран. */
enum class UpscaleFilter {
    LINEAR,
    NEAREST,
}

/** Настройки, которые напрямую влияют на стоимость кадра. */
data class RenderOptimizationSettings(
    val renderScale: Float = 1.0f,
    val dynamicResolutionEnabled: Boolean = false,
    val minimumRenderScale: Float = 0.50f,
    val targetFps: Int = 60,
    val qualityPreset: ShaderQualityPreset = ShaderQualityPreset.ORIGINAL,
    val precision: ShaderPrecision = ShaderPrecision.HIGH,
    val upscaleFilter: UpscaleFilter = UpscaleFilter.LINEAR,
)

/** Сохраняемые пользовательские настройки плеера. */
data class PlayerSettings(
    val playerControlsEnabled: Boolean = true,
    val showStats: Boolean = true,
    val renderBackend: RenderBackend = RenderBackend.OPENGL_ES,
    val optimization: RenderOptimizationSettings = RenderOptimizationSettings(),
)

/** Краткая статистика текущего рендера. */
data class RendererStats(
    val fps: Float = 0f,
    val frameTimeMs: Float = 0f,
    val gpuTimeMs: Float? = null,
    val renderScale: Float = 1f,
    val renderWidth: Int = 0,
    val renderHeight: Int = 0,
    val backendName: String = "OpenGL ES",
    val rendererName: String = "",
)

/** Состояние назначенной текстуры ShaderToy-канала. */
data class TextureChannelState(
    val displayName: String? = null,
    val uri: Uri? = null,
)
