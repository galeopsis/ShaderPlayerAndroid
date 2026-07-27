package com.goodwin.shaderplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.goodwin.shaderplayer.domain.PlayerSettings
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
    }

    val settings: Flow<PlayerSettings> = context.playerSettingsDataStore.data.map { values ->
        PlayerSettings(
            playerControlsEnabled = values[Keys.playerControls] ?: true,
            showStats = values[Keys.showStats] ?: true,
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
}
