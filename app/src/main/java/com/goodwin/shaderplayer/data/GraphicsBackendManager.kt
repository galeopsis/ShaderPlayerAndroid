package com.goodwin.shaderplayer.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.goodwin.shaderplayer.domain.RenderBackend

/**
 * Переключает GLES-драйвер приложения между native OpenGL ES и ANGLE/Vulkan.
 * Для записи Settings.Global требуется одноразовая выдача WRITE_SECURE_SETTINGS через ADB.
 */
class GraphicsBackendManager(
    private val context: Context,
) {
    sealed interface ApplyResult {
        data object Applied : ApplyResult
        data class PermissionRequired(val adbCommand: String) : ApplyResult
        data object Unsupported : ApplyResult
        data class Failure(val reason: String) : ApplyResult
    }

    val vulkanAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)

    fun applyBackend(backend: RenderBackend): ApplyResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return ApplyResult.Unsupported
        }
        if (backend == RenderBackend.VULKAN_ANGLE && !vulkanAvailable) {
            return ApplyResult.Unsupported
        }
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ApplyResult.PermissionRequired(
                adbCommand = "adb shell pm grant ${context.packageName} " +
                    Manifest.permission.WRITE_SECURE_SETTINGS,
            )
        }

        return runCatching {
            updatePerApplicationAngleDriver(
                packageName = context.packageName,
                useAngle = backend == RenderBackend.VULKAN_ANGLE,
            )
            ApplyResult.Applied
        }.getOrElse { error ->
            ApplyResult.Failure(error.message.orEmpty())
        }
    }

    /**
     * Сохраняет настройки других приложений и меняет только запись Shader Player.
     * Для native GLES запись удаляется, что возвращает системный драйвер устройства.
     */
    private fun updatePerApplicationAngleDriver(
        packageName: String,
        useAngle: Boolean,
    ) {
        val resolver = context.contentResolver
        val packageKey = "angle_gl_driver_selection_pkgs"
        val driverKey = "angle_gl_driver_selection_values"

        val packages = Settings.Global.getString(resolver, packageKey)
            .orEmpty()
            .split(',')
            .filter { it.isNotBlank() }
            .toMutableList()
        val drivers = Settings.Global.getString(resolver, driverKey)
            .orEmpty()
            .split(',')
            .filter { it.isNotBlank() }
            .toMutableList()

        while (drivers.size < packages.size) drivers += "default"
        while (packages.size < drivers.size) drivers.removeLast()

        for (index in packages.lastIndex downTo 0) {
            if (packages[index] == packageName) {
                packages.removeAt(index)
                drivers.removeAt(index)
            }
        }

        if (useAngle) {
            packages += packageName
            drivers += "angle"
        }

        val packageValue = packages.joinToString(",").ifEmpty { null }
        val driverValue = drivers.joinToString(",").ifEmpty { null }

        check(Settings.Global.putString(resolver, packageKey, packageValue)) {
            "Не удалось сохранить список пакетов ANGLE"
        }
        check(Settings.Global.putString(resolver, driverKey, driverValue)) {
            "Не удалось сохранить список backend ANGLE"
        }
    }
}
