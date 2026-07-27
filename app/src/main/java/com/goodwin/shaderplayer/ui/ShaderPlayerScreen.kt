package com.goodwin.shaderplayer.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goodwin.shaderplayer.R
import com.goodwin.shaderplayer.rendering.ShaderSurface

/** Основной Compose-экран Android-версии Shader Player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShaderPlayerScreen(
    viewModel: ShaderPlayerViewModel,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.renderController.stats.collectAsStateWithLifecycle()
    val compileError by viewModel.renderController.compileError.collectAsStateWithLifecycle()

    BackHandler(enabled = isFullscreen) {
        onFullscreenChange(false)
    }

    var menuExpanded by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var texturesVisible by remember { mutableStateOf(false) }
    var textureChannelToPick by remember { mutableIntStateOf(0) }

    val shaderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        viewModel.openShader(uri)
    }

    val texturePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        viewModel.selectTexture(textureChannelToPick, uri)
    }

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xE610131C),
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                title = {
                    Text(
                        text = uiState.document?.name ?: stringResource(R.string.built_in_shader),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.textures)) },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                            onClick = {
                                menuExpanded = false
                                texturesVisible = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings)) },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = {
                                menuExpanded = false
                                settingsVisible = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reset_camera)) },
                            onClick = {
                                menuExpanded = false
                                viewModel.resetCamera()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reset_time)) },
                            onClick = {
                                menuExpanded = false
                                viewModel.resetTime()
                            },
                        )
                    }
                },
                )
            }
        },
        bottomBar = {
            if (!isFullscreen) {
                Surface(
                color = Color(0xE610131C),
                tonalElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { shaderPicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FolderOpen, stringResource(R.string.open_shader), tint = Color.White)
                    }
                    IconButton(onClick = viewModel::reloadShader) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.reload_shader), tint = Color.White)
                    }
                    IconButton(onClick = viewModel::togglePause) {
                        Icon(
                            if (uiState.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            if (uiState.paused) stringResource(R.string.resume) else stringResource(R.string.pause),
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = { settingsVisible = true }) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings), tint = Color.White)
                    }
                    IconButton(onClick = { onFullscreenChange(true) }) {
                        Icon(
                            Icons.Default.Fullscreen,
                            stringResource(R.string.enter_fullscreen),
                            tint = Color.White,
                        )
                    }
                }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) {
            ShaderSurface(
                controller = viewModel.renderController,
                modifier = Modifier.fillMaxSize(),
            )

            if (settings.showStats) {
                Text(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .background(Color(0xC8000000), MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    text = stringResource(R.string.fps_format, stats.fps, stats.frameTimeMs),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            if (isFullscreen) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    color = Color(0x99000000),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    IconButton(onClick = { onFullscreenChange(false) }) {
                        Icon(
                            Icons.Default.FullscreenExit,
                            stringResource(R.string.exit_fullscreen),
                            tint = Color.White,
                        )
                    }
                }
            }

            if (uiState.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    if (settingsVisible) {
        ModalBottomSheet(onDismissRequest = { settingsVisible = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.size(16.dp))
                SettingSwitch(
                    title = stringResource(R.string.player_controls),
                    description = stringResource(R.string.player_controls_description),
                    checked = settings.playerControlsEnabled,
                    onCheckedChange = viewModel::setPlayerControlsEnabled,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingSwitch(
                    title = stringResource(R.string.show_stats),
                    description = null,
                    checked = settings.showStats,
                    onCheckedChange = viewModel::setShowStats,
                )

                if (uiState.usesSphereOffset) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(stringResource(R.string.sphere_offset))
                    Slider(
                        value = uiState.sphereOffset,
                        onValueChange = viewModel::setSphereOffset,
                        valueRange = -1.75f..2f,
                    )
                    Text("%.2f".format(uiState.sphereOffset), fontFamily = FontFamily.Monospace)
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.about_limits),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(24.dp))
            }
        }
    }

    if (texturesVisible) {
        ModalBottomSheet(onDismissRequest = { texturesVisible = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                Text(stringResource(R.string.textures), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.size(12.dp))
                uiState.textures.forEachIndexed { index, texture ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.texture_channel, index),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = texture.displayName ?: stringResource(R.string.not_selected),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = { viewModel.clearTexture(index) },
                                enabled = texture.uri != null,
                            ) {
                                Text(stringResource(R.string.clear))
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    textureChannelToPick = index
                                    texturePicker.launch(arrayOf("image/*"))
                                },
                            ) {
                                Text(stringResource(R.string.select_texture))
                            }
                        }
                    }
                    if (index < 3) HorizontalDivider()
                }
                Spacer(Modifier.size(20.dp))
            }
        }
    }

    compileError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.renderController.publishCompileError(null) },
            title = { Text(stringResource(R.string.shader_error)) },
            text = {
                Text(
                    text = error,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.renderController.publishCompileError(null) }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    uiState.userMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::consumeMessage,
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::consumeMessage) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
