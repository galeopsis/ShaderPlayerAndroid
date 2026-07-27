package com.goodwin.shaderplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.goodwin.shaderplayer.domain.PlayerSettings
import com.goodwin.shaderplayer.domain.RenderBackend
import com.goodwin.shaderplayer.domain.RenderOptimizationSettings
import com.goodwin.shaderplayer.domain.ShaderPrecision
import com.goodwin.shaderplayer.domain.ShaderQualityPreset
import com.goodwin.shaderplayer.domain.UpscaleFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playerSettingsDataStore by preferencesDataStore(
    name = "shader_player_settings",
)

/** Хранит небольшие настройки плеера через Preferences DataStore. */
class SettingsRepository(
    private val context: Context,
) {
    private object Keys {
        val playerControls = booleanPreferencesKey("player_controls")
        val showStats = booleanPreferencesKey("show_stats")
        val lastSuccessfulShaderUri = stringPreferencesKey("last_successful_shader_uri")
        val renderBackend = stringPreferencesKey("render_backend")
        val renderScalePercent = intPreferencesKey("render_scale_percent")
        val dynamicResolution = booleanPreferencesKey("dynamic_resolution")
        val minimumRenderScalePercent = intPreferencesKey("minimum_render_scale_percent")
        val targetFps = intPreferencesKey("target_fps")
        val qualityPreset = stringPreferencesKey("quality_preset")
        val shaderPrecision = stringPreferencesKey("shader_precision")
        val upscaleFilter = stringPreferencesKey("upscale_filter")
    }

    val settings: Flow<PlayerSettings> = context.playerSettingsDataStore.data.map { values ->
        PlayerSettings(
            playerControlsEnabled = values[Keys.playerControls] ?: true,
            showStats = values[Keys.showStats] ?: true,
            renderBackend = values[Keys.renderBackend]
                .toEnumOrDefault(RenderBackend.OPENGL_ES),
            optimization = RenderOptimizationSettings(
                renderScale = ((values[Keys.renderScalePercent] ?: 100) / 100f)
                    .coerceIn(0.50f, 1.0f),
                dynamicResolutionEnabled = values[Keys.dynamicResolution] ?: false,
                minimumRenderScale = ((values[Keys.minimumRenderScalePercent] ?: 50) / 100f)
                    .coerceIn(0.50f, 1.0f),
                targetFps = (values[Keys.targetFps] ?: 60)
                    .takeIf { it in SUPPORTED_TARGET_FPS }
                    ?: 60,
                qualityPreset = values[Keys.qualityPreset]
                    .toEnumOrDefault(ShaderQualityPreset.ORIGINAL),
                precision = values[Keys.shaderPrecision]
                    .toEnumOrDefault(ShaderPrecision.HIGH),
                upscaleFilter = values[Keys.upscaleFilter]
                    .toEnumOrDefault(UpscaleFilter.LINEAR),
            ),
        )
    }

    val lastSuccessfulShaderUri: Flow<String?> =
        context.playerSettingsDataStore.data.map { values ->
            values[Keys.lastSuccessfulShaderUri]
        }

    suspend fun setPlayerControlsEnabled(enabled: Boolean) {
        context.playerSettingsDataStore.edit { it[Keys.playerControls] = enabled }
    }

    suspend fun setShowStats(enabled: Boolean) {
        context.playerSettingsDataStore.edit { it[Keys.showStats] = enabled }
    }

    suspend fun setRenderBackend(backend: RenderBackend) {
        context.playerSettingsDataStore.edit { it[Keys.renderBackend] = backend.name }
    }

    suspend fun setRenderScale(scale: Float) {
        context.playerSettingsDataStore.edit {
            it[Keys.renderScalePercent] = (scale.coerceIn(0.50f, 1.0f) * 100f).toInt()
        }
    }

    suspend fun setDynamicResolutionEnabled(enabled: Boolean) {
        context.playerSettingsDataStore.edit { it[Keys.dynamicResolution] = enabled }
    }

    suspend fun setMinimumRenderScale(scale: Float) {
        context.playerSettingsDataStore.edit {
            it[Keys.minimumRenderScalePercent] = (scale.coerceIn(0.50f, 1.0f) * 100f).toInt()
        }
    }

    suspend fun setTargetFps(fps: Int) {
        context.playerSettingsDataStore.edit {
            it[Keys.targetFps] = fps.takeIf { value -> value in SUPPORTED_TARGET_FPS } ?: 60
        }
    }

    suspend fun setQualityPreset(preset: ShaderQualityPreset) {
        context.playerSettingsDataStore.edit { it[Keys.qualityPreset] = preset.name }
    }

    suspend fun setShaderPrecision(precision: ShaderPrecision) {
        context.playerSettingsDataStore.edit { it[Keys.shaderPrecision] = precision.name }
    }

    suspend fun setUpscaleFilter(filter: UpscaleFilter) {
        context.playerSettingsDataStore.edit { it[Keys.upscaleFilter] = filter.name }
    }

    suspend fun applyOptimizationPreset(settings: RenderOptimizationSettings) {
        context.playerSettingsDataStore.edit { values ->
            values[Keys.renderScalePercent] = (settings.renderScale * 100f).toInt()
            values[Keys.dynamicResolution] = settings.dynamicResolutionEnabled
            values[Keys.minimumRenderScalePercent] = (settings.minimumRenderScale * 100f).toInt()
            values[Keys.targetFps] = settings.targetFps
            values[Keys.qualityPreset] = settings.qualityPreset.name
            values[Keys.shaderPrecision] = settings.precision.name
            values[Keys.upscaleFilter] = settings.upscaleFilter.name
        }
    }

    /** Запоминает только документ, который уже успешно скомпилирован renderer'ом. */
    suspend fun setLastSuccessfulShaderUri(uri: String?) {
        context.playerSettingsDataStore.edit { values ->
            if (uri == null) {
                values.remove(Keys.lastSuccessfulShaderUri)
            } else {
                values[Keys.lastSuccessfulShaderUri] = uri
            }
        }
    }

    private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T {
        return this?.let { stored ->
            enumValues<T>().firstOrNull { it.name == stored }
        } ?: default
    }

    private companion object {
        val SUPPORTED_TARGET_FPS = setOf(30, 60, 90, 120)
    }
}
