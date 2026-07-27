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
import androidx.compose.material3.OutlinedButton
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
import com.goodwin.shaderplayer.domain.RenderBackend
import com.goodwin.shaderplayer.domain.ShaderPrecision
import com.goodwin.shaderplayer.domain.ShaderQualityPreset
import com.goodwin.shaderplayer.domain.UpscaleFilter
import com.goodwin.shaderplayer.rendering.ShaderSurface
import java.util.Locale
import kotlin.math.roundToInt

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

    BackHandler(enabled = isFullscreen) { onFullscreenChange(false) }

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
                val gpuText = stats.gpuTimeMs?.let {
                    String.format(Locale.US, "GPU %.2f ms", it)
                } ?: "GPU —"
                val scalePercent = (stats.renderScale * 100f).toInt()
                Text(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .background(Color(0xC8000000), MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    text = buildString {
                        append(String.format(Locale.US, "FPS %.1f · %.2f ms · %s", stats.fps, stats.frameTimeMs, gpuText))
                        append('\n')
                        append("${stats.backendName} · ${stats.renderWidth}×${stats.renderHeight} · ${scalePercent}%")
                        if (stats.rendererName.isNotBlank()) {
                            append('\n')
                            append(stats.rendererName.take(72))
                        }
                    },
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
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
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
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

                Text(stringResource(R.string.optimization_profiles), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { viewModel.applyOptimizationProfile(OptimizationProfile.ORIGINAL) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.profile_original)) }
                OutlinedButton(
                    onClick = { viewModel.applyOptimizationProfile(OptimizationProfile.BALANCED) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.profile_balanced)) }
                OutlinedButton(
                    onClick = { viewModel.applyOptimizationProfile(OptimizationProfile.PERFORMANCE) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.profile_performance)) }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.graphics_api), style = MaterialTheme.typography.titleMedium)
                ChoiceSetting(
                    title = stringResource(R.string.graphics_backend),
                    description = stringResource(R.string.graphics_backend_description),
                    currentText = when (settings.renderBackend) {
                        RenderBackend.OPENGL_ES -> stringResource(R.string.backend_opengl)
                        RenderBackend.VULKAN_ANGLE -> stringResource(R.string.backend_vulkan_angle)
                    },
                    options = listOf(
                        stringResource(R.string.backend_opengl) to { viewModel.setRenderBackend(RenderBackend.OPENGL_ES) },
                        stringResource(R.string.backend_vulkan_angle) to { viewModel.setRenderBackend(RenderBackend.VULKAN_ANGLE) },
                    ),
                )
                Text(
                    text = stringResource(R.string.active_backend, stats.backendName),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!uiState.vulkanAvailable) {
                    Text(stringResource(R.string.vulkan_unavailable), style = MaterialTheme.typography.bodySmall)
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.render_resolution), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.render_scale, (settings.optimization.renderScale * 100f).toInt()))
                Slider(
                    value = settings.optimization.renderScale,
                    onValueChange = viewModel::setRenderScale,
                    valueRange = 0.50f..1.0f,
                    steps = 9,
                )
                SettingSwitch(
                    title = stringResource(R.string.dynamic_resolution),
                    description = stringResource(R.string.dynamic_resolution_description),
                    checked = settings.optimization.dynamicResolutionEnabled,
                    onCheckedChange = viewModel::setDynamicResolutionEnabled,
                )
                if (settings.optimization.dynamicResolutionEnabled) {
                    Text(
                        stringResource(
                            R.string.minimum_render_scale,
                            (settings.optimization.minimumRenderScale * 100f).toInt(),
                        ),
                    )
                    Slider(
                        value = settings.optimization.minimumRenderScale
                            .coerceAtMost(settings.optimization.renderScale),
                        onValueChange = viewModel::setMinimumRenderScale,
                        valueRange = if (settings.optimization.renderScale > 0.50f) {
                            0.50f..settings.optimization.renderScale
                        } else {
                            0.50f..1.0f
                        },
                        steps = if (settings.optimization.renderScale > 0.50f) {
                            (((settings.optimization.renderScale - 0.50f) / 0.05f).roundToInt() - 1)
                                .coerceAtLeast(0)
                        } else {
                            0
                        },
                        enabled = settings.optimization.renderScale > 0.50f,
                    )
                }
                ChoiceSetting(
                    title = stringResource(R.string.target_fps),
                    description = stringResource(R.string.target_fps_description),
                    currentText = "${settings.optimization.targetFps}",
                    options = listOf(30, 60, 90, 120).map { fps ->
                        fps.toString() to { viewModel.setTargetFps(fps) }
                    },
                )
                ChoiceSetting(
                    title = stringResource(R.string.upscale_filter),
                    description = stringResource(R.string.upscale_filter_description),
                    currentText = when (settings.optimization.upscaleFilter) {
                        UpscaleFilter.LINEAR -> stringResource(R.string.filter_linear)
                        UpscaleFilter.NEAREST -> stringResource(R.string.filter_nearest)
                    },
                    options = listOf(
                        stringResource(R.string.filter_linear) to { viewModel.setUpscaleFilter(UpscaleFilter.LINEAR) },
                        stringResource(R.string.filter_nearest) to { viewModel.setUpscaleFilter(UpscaleFilter.NEAREST) },
                    ),
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.shader_optimization), style = MaterialTheme.typography.titleMedium)
                ChoiceSetting(
                    title = stringResource(R.string.shader_quality),
                    description = stringResource(R.string.shader_quality_description),
                    currentText = when (settings.optimization.qualityPreset) {
                        ShaderQualityPreset.ORIGINAL -> stringResource(R.string.quality_original)
                        ShaderQualityPreset.BALANCED -> stringResource(R.string.quality_balanced)
                        ShaderQualityPreset.PERFORMANCE -> stringResource(R.string.quality_performance)
                    },
                    options = listOf(
                        stringResource(R.string.quality_original) to { viewModel.setQualityPreset(ShaderQualityPreset.ORIGINAL) },
                        stringResource(R.string.quality_balanced) to { viewModel.setQualityPreset(ShaderQualityPreset.BALANCED) },
                        stringResource(R.string.quality_performance) to { viewModel.setQualityPreset(ShaderQualityPreset.PERFORMANCE) },
                    ),
                )
                ChoiceSetting(
                    title = stringResource(R.string.shader_precision),
                    description = stringResource(R.string.shader_precision_description),
                    currentText = when (settings.optimization.precision) {
                        ShaderPrecision.HIGH -> stringResource(R.string.precision_high)
                        ShaderPrecision.MEDIUM -> stringResource(R.string.precision_medium)
                    },
                    options = listOf(
                        stringResource(R.string.precision_high) to { viewModel.setShaderPrecision(ShaderPrecision.HIGH) },
                        stringResource(R.string.precision_medium) to { viewModel.setShaderPrecision(ShaderPrecision.MEDIUM) },
                    ),
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                SettingSwitch(
                    title = stringResource(R.string.player_controls),
                    description = stringResource(R.string.player_controls_description),
                    checked = settings.playerControlsEnabled,
                    onCheckedChange = viewModel::setPlayerControlsEnabled,
                )
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
                Text(text = stringResource(R.string.about_limits), style = MaterialTheme.typography.bodySmall)
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
                            ) { Text(stringResource(R.string.clear)) }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    textureChannelToPick = index
                                    texturePicker.launch(arrayOf("image/*"))
                                },
                            ) { Text(stringResource(R.string.select_texture)) }
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
            text = {
                Text(
                    text = message,
                    fontFamily = if (message.contains("adb shell")) FontFamily.Monospace else null,
                )
            },
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
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChoiceSetting(
    title: String,
    description: String?,
    currentText: String,
    options: List<Pair<String, () -> Unit>>,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Box {
            TextButton(onClick = { expanded = true }) { Text(currentText) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            action()
                        },
                    )
                }
            }
        }
    }
}

