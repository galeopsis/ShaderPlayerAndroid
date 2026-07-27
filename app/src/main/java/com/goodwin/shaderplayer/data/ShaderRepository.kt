package com.goodwin.shaderplayer.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import com.goodwin.shaderplayer.domain.ShaderDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Читает шейдеры и изображения через Storage Access Framework. */
class ShaderRepository(
    private val context: Context,
) {
    private val resolver: ContentResolver = context.contentResolver

    /** Загружает текстовый файл вне главного потока. */
    suspend fun readShader(uri: Uri): ShaderDocument = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("ContentResolver returned null stream for $uri")
        val source = decodeShaderSource(bytes)

        ShaderDocument(
            name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "shader.glsl",
            source = source,
            uri = uri,
        )
    }

    /** Читает встроенный ShaderToy-совместимый пример из assets. */
    suspend fun readBuiltInShader(): ShaderDocument = withContext(Dispatchers.IO) {
        val source = context.assets.open("default_shader.glsl").bufferedReader().use {
            it.readText()
        }
        ShaderDocument(name = "default_shader.glsl", source = source)
    }

    /** Декодирует и переворачивает изображение для координат OpenGL. */
    suspend fun readTexture(uri: Uri): Pair<String, Bitmap> = withContext(Dispatchers.IO) {
        val decoded = resolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: throw IOException("Unable to decode bitmap: $uri")

        val matrix = Matrix().apply { preScale(1f, -1f) }
        val flipped = Bitmap.createBitmap(
            decoded,
            0,
            0,
            decoded.width,
            decoded.height,
            matrix,
            true,
        )
        if (flipped !== decoded) {
            decoded.recycle()
        }

        (queryDisplayName(uri) ?: uri.lastPathSegment ?: "texture") to flipped
    }


    /**
     * Поддерживает UTF-8/UTF-16 и старые Windows-1251 файлы Bonzomatic.
     * Некорректный UTF-8 не должен превращать исходник шейдера в набор U+FFFD.
     */
    private fun decodeShaderSource(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }

        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, charset("windows-1251"))
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    }
}
