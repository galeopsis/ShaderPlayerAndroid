package com.goodwin.shaderplayer.domain

import android.net.Uri

/** Открытый пользователем текстовый шейдер. */
data class ShaderDocument(
    val name: String,
    val source: String,
    val uri: Uri? = null,
)

/** Сохраняемые пользовательские настройки плеера. */
data class PlayerSettings(
    val playerControlsEnabled: Boolean = true,
    val showStats: Boolean = true,
)

/** Краткая статистика текущего OpenGL-рендера. */
data class RendererStats(
    val fps: Float = 0f,
    val frameTimeMs: Float = 0f,
)

/** Состояние назначенной текстуры ShaderToy-канала. */
data class TextureChannelState(
    val displayName: String? = null,
    val uri: Uri? = null,
)
