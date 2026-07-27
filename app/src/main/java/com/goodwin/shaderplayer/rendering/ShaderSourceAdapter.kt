package com.goodwin.shaderplayer.rendering

import com.goodwin.shaderplayer.domain.ShaderPrecision
import com.goodwin.shaderplayer.domain.ShaderQualityPreset

/**
 * Приводит ShaderToy/Bonzomatic/desktop GLSL к GLSL ES 3.00.
 *
 * Помимо смены entry point адаптер устраняет распространённые различия между
 * desktop GLSL и OpenGL ES: sampler1D, texture1D, неявные int -> float и
 * динамические глобальные инициализаторы старых Bonzomatic-шейдеров.
 */
class ShaderSourceAdapter {
    data class Result(
        val source: String,
        val usesSphereOffset: Boolean,
        val cameraPatched: Boolean,
    )

    fun adapt(
        source: String,
        playerControlsEnabled: Boolean,
        qualityPreset: ShaderQualityPreset = ShaderQualityPreset.ORIGINAL,
        precision: ShaderPrecision = ShaderPrecision.HIGH,
    ): Result {
        val sourceParts = splitDesktopDirectives(applyQualityPreset(source, qualityPreset))
        val originalBody = sourceParts.body
        val isShaderToy = MAIN_IMAGE_REGEX.containsMatchIn(originalBody)
        val oneDimensionalSamplers = findOneDimensionalSamplers(originalBody)

        var body = rewriteLegacyTextureSyntax(originalBody, oneDimensionalSamplers)
        body = rewriteCStyleArrayInitializers(body)
            .replace(Regex("\\blayout\\s*\\([^)]*location\\s*=\\s*\\d+[^)]*\\)\\s*in\\s+"), "in ")
            .replace(Regex("\\btexture2D\\s*\\("), "texture(")
            .replace(Regex("\\btextureCube\\s*\\("), "texture(")
            .replace(Regex("\\bgl_FragColor\\b"), "spFragColor")

        /*
         * ShaderToy-код уже рассчитан на строгую типизацию GLSL ES. Числовые
         * desktop-преобразования применяются только к Bonzomatic/main()-шейдерам,
         * иначе uint/bitwise-код может быть повреждён (например, 1u -> 1.0u).
         */
        if (!isShaderToy) {
            val integerSymbols =
                findConstIntNames(body) +
                    findIntegerMacroNames(body) +
                    findIntegerSymbols(body)
            val dynamicGlobals = rewriteDynamicGlobalInitializers(body, integerSymbols)
            body = dynamicGlobals.source
            body = rewriteFloatingPointInitializers(body, integerSymbols)

            val floatSymbols = findFloatingPointSymbols(body)
            val functionSignatures = findFunctionSignatures(body)
            body = rewriteTypedFunctionCalls(body, functionSignatures, floatSymbols, integerSymbols)
            body = rewriteFloatingPointBuiltins(body, integerSymbols)
            body = rewriteFloatingPointAssignments(body, floatSymbols, integerSymbols)
            body = rewriteFloatingPointConditions(body, floatSymbols)
            body = rewriteFloatingPointReturns(body, integerSymbols)
            body = injectDynamicGlobalAssignments(body, dynamicGlobals.assignments)
        }

        var cameraPatched = false

        if (playerControlsEnabled) {
            val patched = injectOrbitCamera(body)
            body = patched.first
            cameraPatched = patched.second
        }

        val declarations = buildString {
            appendLine("#version 300 es")
            appendLine(
                when (precision) {
                    ShaderPrecision.HIGH -> "precision highp float;"
                    ShaderPrecision.MEDIUM -> "precision mediump float;"
                },
            )
            appendLine("precision highp int;")
            appendLine("#ifndef iGlobalTime")
            appendLine("#define iGlobalTime iTime")
            appendLine("#endif")
            appendLine(LEGACY_TEXTURE_HELPERS)

            appendMissingUniform(body, "iResolution", "uniform vec3 iResolution;")
            appendMissingUniform(body, "iTime", "uniform float iTime;")
            appendMissingUniform(body, "iTimeDelta", "uniform float iTimeDelta;")
            appendMissingUniform(body, "iFrameRate", "uniform float iFrameRate;")
            appendMissingUniform(body, "iFrame", "uniform int iFrame;")
            appendMissingUniform(body, "iMouse", "uniform vec4 iMouse;")
            appendMissingUniform(body, "iDate", "uniform vec4 iDate;")
            appendMissingUniform(body, "iSampleRate", "uniform float iSampleRate;")

            appendMissingUniform(body, "fGlobalTime", "uniform float fGlobalTime;")
            appendMissingUniform(body, "fFrameTime", "uniform float fFrameTime;")
            appendMissingUniform(body, "fFrameRate", "uniform float fFrameRate;")
            appendMissingUniform(body, "v2Resolution", "uniform vec2 v2Resolution;")
            appendMissingUniform(body, "v2Mouse", "uniform vec2 v2Mouse;")
            appendMissingUniform(body, "v4Mouse", "uniform vec4 v4Mouse;")

            appendMissingUniform(body, "iChannelTime", "uniform float iChannelTime[4];")
            appendMissingUniform(body, "iChannelResolution", "uniform vec3 iChannelResolution[4];")
            for (index in 0..3) {
                appendMissingUniform(body, "iChannel$index", "uniform sampler2D iChannel$index;")
            }

            // Стандартные имена текстур Bonzomatic. В старых шейдерах их
            // объявления часто закомментированы, хотя сами sampler-переменные
            // продолжают использоваться в коде. На desktop их предоставляет
            // окружение Bonzomatic, поэтому Android-плеер объявляет аналоги.
            LEGACY_SAMPLER_BINDINGS.keys.forEach { samplerName ->
                appendMissingUniform(
                    body,
                    samplerName,
                    "uniform sampler2D $samplerName;",
                )
            }

            if (playerControlsEnabled) {
                appendLine("uniform vec2 spSceneRotation;")
                appendLine("uniform vec2 spScenePan;")
                appendLine("uniform float spSceneZoom;")
                appendLine(SCENE_HELPERS)
            }

            if (isShaderToy || body.contains("spFragColor")) {
                appendLine("layout(location = 0) out vec4 spFragColor;")
            }
        }

        val finalBody = when {
            isShaderToy && !MAIN_FUNCTION_REGEX.containsMatchIn(body) -> {
                val coordinate = if (playerControlsEnabled && !cameraPatched) {
                    "spTransformFragCoord(gl_FragCoord.xy)"
                } else {
                    "gl_FragCoord.xy"
                }
                "#line 1\n$body\nvoid main() { mainImage(spFragColor, $coordinate); }\n"
            }

            else -> "#line 1\n$body"
        }

        return Result(
            source = declarations + "\n" + finalBody,
            usesSphereOffset = IDENTIFIER_SPHERE_OFFSET.containsMatchIn(body),
            cameraPatched = cameraPatched,
        )
    }

    /**
     * Уменьшает только известные константы стоимости ray marching.
     * Исходный режим не меняет пользовательский код.
     */
    private fun applyQualityPreset(
        source: String,
        preset: ShaderQualityPreset,
    ): String {
        if (preset == ShaderQualityPreset.ORIGINAL) return source

        val limits = when (preset) {
            ShaderQualityPreset.ORIGINAL -> emptyMap()
            ShaderQualityPreset.BALANCED -> mapOf(
                "NUM_STEPS" to 24,
                "MAX_STEPS" to 72,
                "RAYMARCH_STEPS" to 72,
                "ITER_GEOMETRY" to 2,
                "ITER_FRAGMENT" to 4,
                "AA" to 2,
            )
            ShaderQualityPreset.PERFORMANCE -> mapOf(
                "NUM_STEPS" to 18,
                "MAX_STEPS" to 48,
                "RAYMARCH_STEPS" to 48,
                "ITER_GEOMETRY" to 2,
                "ITER_FRAGMENT" to 3,
                "AA" to 1,
            )
        }

        var result = source
        limits.forEach { (name, limit) ->
            val constPattern = Regex(
                "(\\bconst\\s+int\\s+${Regex.escape(name)}\\s*=\\s*)(\\d+)(\\s*;)",
            )
            result = constPattern.replace(result) { match ->
                val original = match.groupValues[2].toIntOrNull() ?: return@replace match.value
                match.groupValues[1] + minOf(original, limit) + match.groupValues[3]
            }

            val definePattern = Regex(
                "(?m)^(\\s*#define\\s+${Regex.escape(name)}\\s+)(\\d+)(\\s*(?://.*)?)$",
            )
            result = definePattern.replace(result) { match ->
                val original = match.groupValues[2].toIntOrNull() ?: return@replace match.value
                match.groupValues[1] + minOf(original, limit) + match.groupValues[3]
            }
        }

        if (preset == ShaderQualityPreset.PERFORMANCE) {
            result = Regex("(?m)^(\\s*#define\\s+CLOUD_QUALITY\\s+)\\d+").replace(result) {
                it.groupValues[1] + "0"
            }
        }
        return result
    }

    private fun StringBuilder.appendMissingUniform(
        source: String,
        name: String,
        declaration: String,
    ) {
        val declarationRegex = Regex("\\buniform\\s+[^;]*\\b${Regex.escape(name)}\\b")
        // Комментарии не являются объявлениями. Раньше строка вида
        // // uniform sampler2D texTex2; блокировала автоматическое добавление
        // sampler и приводила к ошибке undeclared identifier.
        if (!declarationRegex.containsMatchIn(removeComments(source))) {
            appendLine(declaration)
        }
    }

    private fun removeComments(source: String): String {
        val result = StringBuilder(source.length)
        var index = 0
        var lineComment = false
        var blockComment = false

        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)

            when {
                lineComment -> {
                    if (current == '\n') {
                        lineComment = false
                        result.append('\n')
                    } else {
                        result.append(' ')
                    }
                    index++
                }

                blockComment -> {
                    if (current == '*' && next == '/') {
                        result.append("  ")
                        index += 2
                        blockComment = false
                    } else {
                        result.append(if (current == '\n') '\n' else ' ')
                        index++
                    }
                }

                current == '/' && next == '/' -> {
                    result.append("  ")
                    index += 2
                    lineComment = true
                }

                current == '/' && next == '*' -> {
                    result.append("  ")
                    index += 2
                    blockComment = true
                }

                else -> {
                    result.append(current)
                    index++
                }
            }
        }
        return result.toString()
    }

    private data class SourceParts(
        val body: String,
    )

    /**
     * Удаляет desktop-директивы, которые нельзя переносить после #version 300 es.
     * Производные dFdx/dFdy уже входят в ядро OpenGL ES 3.0.
     */
    private fun splitDesktopDirectives(source: String): SourceParts {
        val body = buildString {
            source
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lineSequence()
                .forEach { line ->
                    val trimmed = line.trimStart()
                    when {
                        trimmed.startsWith("#version") -> Unit
                        trimmed.startsWith("precision ") -> Unit
                        trimmed.startsWith("#extension") -> Unit
                        else -> appendLine(line)
                    }
                }
        }
        return SourceParts(body = body)
    }

    /**
     * GLSL desktop допускает C-подобный initializer массива:
     * `vec3 values[4] = { ... };`. В GLSL ES 3.00 требуется конструктор
     * массива `vec3[4](...)`.
     */
    private fun rewriteCStyleArrayInitializers(source: String): String {
        val replacements = mutableListOf<Replacement>()

        for (match in C_STYLE_ARRAY_INITIALIZER.findAll(source)) {
            val type = match.groupValues[1]
            val size = match.groupValues[3].trim()
            val openBrace = source.indexOf('{', match.range.first)
            if (openBrace < 0) continue
            val closeBrace = findMatching(source, openBrace, '{', '}') ?: continue

            val semicolon = skipWhitespace(source, closeBrace + 1)
            if (semicolon >= source.length || source[semicolon] != ';') continue

            val elements = source
                .substring(openBrace + 1, closeBrace)
                .replace(Regex(",\\s*$"), "")
                .trim()

            replacements += Replacement(
                start = openBrace,
                endExclusive = closeBrace + 1,
                value = "$type[$size]($elements)",
            )
        }

        return applyReplacements(source, replacements)
    }

    private fun findOneDimensionalSamplers(source: String): Set<String> {
        return SAMPLER_1D_DECLARATION.findAll(source)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun findConstIntNames(source: String): Set<String> {
        return CONST_INT_DECLARATION.findAll(source)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun findIntegerMacroNames(source: String): Set<String> {
        return INTEGER_MACRO_DECLARATION.findAll(source)
            .map { it.groupValues[1] }
            .toSet()
    }

    /** Находит локальные, глобальные и параметрические int/uint-переменные. */
    private fun findIntegerSymbols(source: String): Set<String> {
        return INTEGER_SYMBOL_DECLARATION.findAll(removeComments(source))
            .map { it.groupValues[1] }
            .toSet()
    }

    /** Переводит sampler1D/texture1D на эмуляцию через sampler2D высотой 1 texel. */
    private fun rewriteLegacyTextureSyntax(
        source: String,
        samplerNames: Set<String>,
    ): String {
        var result = source
            .replace(Regex("\\bisampler1D\\b"), "isampler2D")
            .replace(Regex("\\busampler1D\\b"), "usampler2D")
            .replace(Regex("\\bsampler1DShadow\\b"), "sampler2DShadow")
            .replace(Regex("\\bsampler1D\\b"), "sampler2D")
            .replace(Regex("\\btexture1DProjLod\\s*\\("), "spTexture1DProjLod(")
            .replace(Regex("\\btexture1DProj\\s*\\("), "spTexture1DProj(")
            .replace(Regex("\\btexture1DLod\\s*\\("), "spTexture1DLod(")
            .replace(Regex("\\btexture1D\\s*\\("), "spTexture1D(")

        if (samplerNames.isNotEmpty()) {
            result = rewriteCallsForOneDimensionalSamplers(
                source = result,
                functionName = "texture",
                samplerNames = samplerNames,
                replacementName = "spTexture1D",
            )
            result = rewriteCallsForOneDimensionalSamplers(
                source = result,
                functionName = "textureLod",
                samplerNames = samplerNames,
                replacementName = "spTexture1DLod",
            )
            result = rewriteCallsForOneDimensionalSamplers(
                source = result,
                functionName = "texelFetch",
                samplerNames = samplerNames,
                replacementName = "spTexelFetch1D",
            )
        }
        return result
    }

    private fun rewriteCallsForOneDimensionalSamplers(
        source: String,
        functionName: String,
        samplerNames: Set<String>,
        replacementName: String,
    ): String {
        val replacements = mutableListOf<Replacement>()
        var searchFrom = 0

        while (searchFrom < source.length) {
            val nameIndex = source.indexOf(functionName, searchFrom)
            if (nameIndex < 0) break

            if (!hasIdentifierBoundaries(source, nameIndex, functionName.length)) {
                searchFrom = nameIndex + functionName.length
                continue
            }

            val openParenthesis = skipWhitespace(source, nameIndex + functionName.length)
            if (openParenthesis >= source.length || source[openParenthesis] != '(') {
                searchFrom = nameIndex + functionName.length
                continue
            }

            val closeParenthesis = findMatching(source, openParenthesis, '(', ')')
                ?: break
            val arguments = splitArguments(source.substring(openParenthesis + 1, closeParenthesis))
            val firstArgument = arguments.firstOrNull()?.trim().orEmpty()

            if (firstArgument in samplerNames) {
                replacements += Replacement(
                    start = nameIndex,
                    endExclusive = nameIndex + functionName.length,
                    value = replacementName,
                )
            }
            searchFrom = closeParenthesis + 1
        }

        return applyReplacements(source, replacements)
    }

    /**
     * Старые Bonzomatic-шейдеры иногда вычисляют time/beat в глобальной области.
     * В OpenGL ES глобальный initializer обязан быть константным, поэтому такие
     * значения инициализируются в main() перед первым использованием.
     */
    private data class DynamicGlobalRewrite(
        val source: String,
        val assignments: List<String>,
    )

    /**
     * OpenGL ES запрещает initializer глобальной переменной, зависящий от
     * uniform. Вместо макроса (он ломает одноимённые параметры функций)
     * оставляем глобальное объявление и выполняем присваивание в main().
     */
    private fun rewriteDynamicGlobalInitializers(
        source: String,
        constIntNames: Set<String>,
    ): DynamicGlobalRewrite {
        val replacements = mutableListOf<Replacement>()
        val assignments = mutableListOf<String>()

        for (range in findTopLevelStatements(source)) {
            val statement = source.substring(range.first, range.last + 1)
            val match = SIMPLE_FLOAT_GLOBAL.matchEntire(statement) ?: continue
            val expression = match.groupValues[5]
            if (!DYNAMIC_GLOBAL_IDENTIFIER.containsMatchIn(expression)) continue

            val leadingText = match.groupValues[1]
            val type = match.groupValues[3]
            val name = match.groupValues[4]
            val normalizedExpression = normalizeFloatExpression(expression, constIntNames)
            replacements += Replacement(
                start = range.first,
                endExclusive = range.last + 1,
                value = "$leadingText$type $name = ${defaultValueForType(type)};",
            )
            assignments += "$name = $normalizedExpression;"
        }

        return DynamicGlobalRewrite(
            source = applyReplacements(source, replacements),
            assignments = assignments,
        )
    }

    private fun defaultValueForType(type: String): String = when (type) {
        "float" -> "0.0"
        "vec2" -> "vec2(0.0)"
        "vec3" -> "vec3(0.0)"
        "vec4" -> "vec4(0.0)"
        "mat2" -> "mat2(1.0)"
        "mat3" -> "mat3(1.0)"
        "mat4" -> "mat4(1.0)"
        else -> "0.0"
    }

    private fun injectDynamicGlobalAssignments(
        source: String,
        assignments: List<String>,
    ): String {
        if (assignments.isEmpty()) return source
        val main = findFunctionDefinition(source, "main") ?: return source
        val indentation = indentationAt(source, main.openBrace) + "    "
        val insertion = buildString {
            append('\n')
            assignments.forEach { assignment ->
                append(indentation).append(assignment).append('\n')
            }
        }
        return source.substring(0, main.openBrace + 1) + insertion +
            source.substring(main.openBrace + 1)
    }

    /** Добавляет явные float-литералы и casts в initializers float/vec/mat. */
    private fun rewriteFloatingPointInitializers(
        source: String,
        constIntNames: Set<String>,
    ): String {
        return FLOATING_DECLARATION.replace(source) { match ->
            val prefix = match.groupValues[1]
            val expression = match.groupValues[2]
            val suffix = match.groupValues[3]
            prefix + normalizeFloatExpression(expression, constIntNames) + suffix
        }
    }

    /** Исправляет clamp(x, 0, 1), mix(..., 1) и похожие desktop-вызовы. */
    private fun rewriteFloatingPointBuiltins(
        source: String,
        constIntNames: Set<String>,
    ): String {
        var result = source
        for (functionName in FLOAT_BUILTIN_NAMES) {
            result = rewriteBuiltinCalls(result, functionName, constIntNames)
        }
        return result
    }

    private fun rewriteBuiltinCalls(
        source: String,
        functionName: String,
        constIntNames: Set<String>,
    ): String {
        val replacements = mutableListOf<Replacement>()
        var searchFrom = 0

        while (searchFrom < source.length) {
            val nameIndex = source.indexOf(functionName, searchFrom)
            if (nameIndex < 0) break
            if (!hasIdentifierBoundaries(source, nameIndex, functionName.length)) {
                searchFrom = nameIndex + functionName.length
                continue
            }

            val openParenthesis = skipWhitespace(source, nameIndex + functionName.length)
            if (openParenthesis >= source.length || source[openParenthesis] != '(') {
                searchFrom = nameIndex + functionName.length
                continue
            }
            val closeParenthesis = findMatching(source, openParenthesis, '(', ')') ?: break
            val rawArguments = source.substring(openParenthesis + 1, closeParenthesis)
            val arguments = splitArguments(rawArguments)

            val allInteger = arguments.isNotEmpty() && arguments.all { argument ->
                val trimmed = argument.trim()
                INTEGER_LITERAL.matches(trimmed) || trimmed in constIntNames
            }
            if (!allInteger) {
                val normalized = arguments.joinToString(", ") {
                    normalizeFloatExpression(it, constIntNames)
                }
                if (normalized != rawArguments) {
                    replacements += Replacement(
                        start = openParenthesis + 1,
                        endExclusive = closeParenthesis,
                        value = normalized,
                    )
                }
            }
            searchFrom = closeParenthesis + 1
        }

        return applyReplacements(source, replacements)
    }

    private fun normalizeFloatExpression(
        expression: String,
        constIntNames: Set<String>,
    ): String {
        val source = rewriteMixedFloatingModulo(expression, constIntNames)
        val output = StringBuilder(source.length + 24)
        val functionStack = mutableListOf<String?>()
        var pendingIdentifier: String? = null
        var index = 0
        var bracketDepth = 0

        while (index < source.length) {
            val character = source[index]
            val protectedConstructor = functionStack.any {
                it in INTEGER_PRESERVING_CONSTRUCTORS
            }

            when {
                character == '[' -> {
                    bracketDepth++
                    pendingIdentifier = null
                    output.append(character)
                    index++
                }

                character == ']' -> {
                    bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                    pendingIdentifier = null
                    output.append(character)
                    index++
                }

                character == '(' -> {
                    functionStack += pendingIdentifier
                    pendingIdentifier = null
                    output.append(character)
                    index++
                }

                character == ')' -> {
                    if (functionStack.isNotEmpty()) functionStack.removeLast()
                    pendingIdentifier = null
                    output.append(character)
                    index++
                }

                character.isLetter() || character == '_' -> {
                    val tokenStart = index
                    index++
                    while (index < source.length &&
                        (source[index].isLetterOrDigit() || source[index] == '_')
                    ) {
                        index++
                    }
                    val identifier = source.substring(tokenStart, index)
                    val alreadyCasted = output.endsWithIgnoringWhitespace("float(")
                    if (
                        bracketDepth == 0 &&
                        !protectedConstructor &&
                        identifier in constIntNames &&
                        !alreadyCasted
                    ) {
                        output.append("float(").append(identifier).append(')')
                    } else {
                        output.append(identifier)
                    }
                    pendingIdentifier = identifier
                }

                character.isDigit() && isNumberStart(source, index) -> {
                    val tokenStart = index
                    var hasDot = false
                    var hasExponent = false
                    index++
                    while (index < source.length) {
                        val current = source[index]
                        when {
                            current.isDigit() -> index++
                            current == '.' -> {
                                hasDot = true
                                index++
                            }
                            current == 'e' || current == 'E' -> {
                                hasExponent = true
                                index++
                                if (index < source.length &&
                                    (source[index] == '+' || source[index] == '-')
                                ) {
                                    index++
                                }
                            }
                            current == 'f' || current == 'F' ||
                                current == 'u' || current == 'U' -> index++
                            else -> break
                        }
                    }
                    val number = source.substring(tokenStart, index)
                    output.append(number)
                    val suffix = number.lastOrNull()?.lowercaseChar()
                    if (
                        bracketDepth == 0 &&
                        !protectedConstructor &&
                        !hasDot &&
                        !hasExponent &&
                        suffix != 'f' &&
                        suffix != 'u'
                    ) {
                        output.append(".0")
                    }
                    pendingIdentifier = null
                }

                character.isWhitespace() -> {
                    output.append(character)
                    index++
                }

                else -> {
                    pendingIdentifier = null
                    output.append(character)
                    index++
                }
            }
        }
        return output.toString()
    }

    /**
     * В GLSL ES оператор % определён только для целых типов. Старые desktop-
     * шейдеры встречаются с выражениями `i % 2.0`; они переводятся в mod().
     */
    private fun rewriteMixedFloatingModulo(
        expression: String,
        integerSymbols: Set<String>,
    ): String {
        if ('%' !in expression || integerSymbols.isEmpty()) return expression

        return MIXED_INT_FLOAT_MODULO.replace(expression) { match ->
            val left = match.groupValues[1]
            if (left in integerSymbols) {
                "mod(float($left), ${match.groupValues[2]})"
            } else {
                match.value
            }
        }
    }

    private fun StringBuilder.endsWithIgnoringWhitespace(value: String): Boolean {
        var sourceIndex = length - 1
        while (sourceIndex >= 0 && this[sourceIndex].isWhitespace()) sourceIndex--
        var valueIndex = value.length - 1
        while (sourceIndex >= 0 && valueIndex >= 0) {
            if (this[sourceIndex] != value[valueIndex]) return false
            sourceIndex--
            valueIndex--
        }
        return valueIndex < 0
    }

    private fun isNumberStart(source: String, index: Int): Boolean {
        if (!source[index].isDigit()) return false
        if (index == 0) return true
        val previous = source[index - 1]
        return !previous.isLetterOrDigit() && previous != '_' && previous != '.'
    }

    private enum class ParameterKind {
        FLOATING,
        INTEGER,
        OTHER,
    }

    private data class FunctionSignature(
        val parameterKinds: List<ParameterKind>,
    )

    private data class FunctionDefinition(
        val name: String,
        val returnType: String,
        val openBrace: Int,
        val closeBrace: Int,
    )

    private fun findFloatingPointSymbols(source: String): Set<String> {
        return FLOATING_SYMBOL_DECLARATION.findAll(source)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun findFunctionSignatures(source: String): Map<String, FunctionSignature> {
        return findFunctionDefinitions(source).associate { definition ->
            val nameIndex = source.lastIndexOf(definition.name, definition.openBrace)
            val openParenthesis = source.indexOf('(', nameIndex)
            val closeParenthesis = findMatching(source, openParenthesis, '(', ')')
                ?: return@associate definition.name to FunctionSignature(emptyList())
            val parameters = splitArguments(
                source.substring(openParenthesis + 1, closeParenthesis),
            ).filterNot { it.trim() == "void" }.map { parameter ->
                val type = PARAMETER_TYPE.find(parameter)?.groupValues?.get(1)
                when {
                    type == null -> ParameterKind.OTHER
                    isFloatingType(type) -> ParameterKind.FLOATING
                    isIntegerType(type) -> ParameterKind.INTEGER
                    else -> ParameterKind.OTHER
                }
            }
            definition.name to FunctionSignature(parameters)
        }
    }

    private fun findFunctionDefinitions(source: String): List<FunctionDefinition> {
        val result = mutableListOf<FunctionDefinition>()
        for (match in FUNCTION_HEADER.findAll(source)) {
            val openParenthesis = source.indexOf('(', match.range.first)
            if (openParenthesis < 0) continue
            val closeParenthesis = findMatching(source, openParenthesis, '(', ')') ?: continue
            val openBrace = skipWhitespace(source, closeParenthesis + 1)
            if (openBrace >= source.length || source[openBrace] != '{') continue
            val closeBrace = findMatching(source, openBrace, '{', '}') ?: continue
            result += FunctionDefinition(
                name = match.groupValues[2],
                returnType = match.groupValues[1],
                openBrace = openBrace,
                closeBrace = closeBrace,
            )
        }
        return result
    }

    private fun findFunctionDefinition(source: String, name: String): FunctionDefinition? {
        return findFunctionDefinitions(source).firstOrNull { it.name == name }
    }

    private fun rewriteTypedFunctionCalls(
        source: String,
        signatures: Map<String, FunctionSignature>,
        floatSymbols: Set<String>,
        constIntNames: Set<String>,
    ): String {
        var result = source

        signatures.forEach { (name, signature) ->
            result = rewriteCallsWithExpectedTypes(
                source = result,
                functionName = name,
                expectedKinds = signature.parameterKinds,
                repeatLastKind = false,
                constIntNames = constIntNames,
            )
        }

        FLOATING_CONSTRUCTORS.forEach { constructor ->
            result = rewriteCallsWithExpectedTypes(
                source = result,
                functionName = constructor,
                expectedKinds = listOf(ParameterKind.FLOATING),
                repeatLastKind = true,
                constIntNames = constIntNames,
            )
        }

        FLOAT_UNARY_BUILTINS.forEach { functionName ->
            result = rewriteFloatBuiltinWhenNeeded(
                source = result,
                functionName = functionName,
                floatSymbols = floatSymbols,
                constIntNames = constIntNames,
            )
        }

        return result
    }

    private fun rewriteCallsWithExpectedTypes(
        source: String,
        functionName: String,
        expectedKinds: List<ParameterKind>,
        repeatLastKind: Boolean,
        constIntNames: Set<String>,
    ): String {
        if (expectedKinds.isEmpty()) return source
        val replacements = mutableListOf<Replacement>()
        var searchFrom = 0

        while (searchFrom < source.length) {
            val nameIndex = source.indexOf(functionName, searchFrom)
            if (nameIndex < 0) break
            if (!hasIdentifierBoundaries(source, nameIndex, functionName.length)) {
                searchFrom = nameIndex + functionName.length
                continue
            }

            val openParenthesis = skipWhitespace(source, nameIndex + functionName.length)
            if (openParenthesis >= source.length || source[openParenthesis] != '(') {
                searchFrom = nameIndex + functionName.length
                continue
            }
            val closeParenthesis = findMatching(source, openParenthesis, '(', ')') ?: break
            val rawArguments = source.substring(openParenthesis + 1, closeParenthesis)
            val arguments = splitArguments(rawArguments)
            val normalized = arguments.mapIndexed { index, argument ->
                val kind = expectedKinds.getOrNull(index)
                    ?: if (repeatLastKind) expectedKinds.last() else ParameterKind.OTHER
                if (kind == ParameterKind.FLOATING) {
                    normalizeFloatExpression(argument, constIntNames)
                } else {
                    argument
                }
            }.joinToString(",")

            if (normalized != rawArguments) {
                replacements += Replacement(
                    start = openParenthesis + 1,
                    endExclusive = closeParenthesis,
                    value = normalized,
                )
            }
            searchFrom = closeParenthesis + 1
        }

        return applyReplacements(source, replacements)
    }

    private fun rewriteFloatBuiltinWhenNeeded(
        source: String,
        functionName: String,
        floatSymbols: Set<String>,
        constIntNames: Set<String>,
    ): String {
        val replacements = mutableListOf<Replacement>()
        var searchFrom = 0

        while (searchFrom < source.length) {
            val nameIndex = source.indexOf(functionName, searchFrom)
            if (nameIndex < 0) break
            if (!hasIdentifierBoundaries(source, nameIndex, functionName.length)) {
                searchFrom = nameIndex + functionName.length
                continue
            }
            val openParenthesis = skipWhitespace(source, nameIndex + functionName.length)
            if (openParenthesis >= source.length || source[openParenthesis] != '(') {
                searchFrom = nameIndex + functionName.length
                continue
            }
            val closeParenthesis = findMatching(source, openParenthesis, '(', ')') ?: break
            val rawArguments = source.substring(openParenthesis + 1, closeParenthesis)
            if (containsFloatingExpression(rawArguments, floatSymbols)) {
                val normalized = normalizeFloatExpression(rawArguments, constIntNames)
                if (normalized != rawArguments) {
                    replacements += Replacement(
                        start = openParenthesis + 1,
                        endExclusive = closeParenthesis,
                        value = normalized,
                    )
                }
            }
            searchFrom = closeParenthesis + 1
        }
        return applyReplacements(source, replacements)
    }

    private fun rewriteFloatingPointAssignments(
        source: String,
        floatSymbols: Set<String>,
        constIntNames: Set<String>,
    ): String {
        return FLOATING_ASSIGNMENT.replace(source) { match ->
            val declaredType = match.groupValues[2]
            val baseName = match.groupValues[3]
            val floatingTarget = when {
                declaredType.isNotEmpty() -> isFloatingType(declaredType)
                else -> baseName in floatSymbols
            }
            if (!floatingTarget) {
                match.value
            } else {
                match.groupValues[1] +
                    normalizeFloatExpression(match.groupValues[4], constIntNames) +
                    match.groupValues[5]
            }
        }
    }

    private fun rewriteFloatingPointConditions(
        source: String,
        floatSymbols: Set<String>,
    ): String {
        val replacements = mutableListOf<Replacement>()
        for (keyword in listOf("if", "while")) {
            var searchFrom = 0
            while (searchFrom < source.length) {
                val keywordIndex = source.indexOf(keyword, searchFrom)
                if (keywordIndex < 0) break
                if (!hasIdentifierBoundaries(source, keywordIndex, keyword.length)) {
                    searchFrom = keywordIndex + keyword.length
                    continue
                }
                val openParenthesis = skipWhitespace(source, keywordIndex + keyword.length)
                if (openParenthesis >= source.length || source[openParenthesis] != '(') {
                    searchFrom = keywordIndex + keyword.length
                    continue
                }
                val closeParenthesis = findMatching(source, openParenthesis, '(', ')') ?: break
                val condition = source.substring(openParenthesis + 1, closeParenthesis)
                if (containsFloatingExpression(condition, floatSymbols)) {
                    val normalized = normalizeFloatExpression(condition, emptySet())
                    if (normalized != condition) {
                        replacements += Replacement(
                            start = openParenthesis + 1,
                            endExclusive = closeParenthesis,
                            value = normalized,
                        )
                    }
                }
                searchFrom = closeParenthesis + 1
            }
        }
        return applyReplacements(source, replacements)
    }

    private fun rewriteFloatingPointReturns(
        source: String,
        constIntNames: Set<String>,
    ): String {
        val replacements = mutableListOf<Replacement>()
        findFunctionDefinitions(source)
            .filter { isFloatingType(it.returnType) }
            .forEach { function ->
                val bodyStart = function.openBrace + 1
                val body = source.substring(bodyStart, function.closeBrace)
                RETURN_EXPRESSION.findAll(body).forEach { match ->
                    val expression = match.groupValues[1]
                    replacements += Replacement(
                        start = bodyStart + match.range.first + match.value.indexOf(expression),
                        endExclusive = bodyStart + match.range.first + match.value.indexOf(expression) + expression.length,
                        value = normalizeFloatExpression(expression, constIntNames),
                    )
                }
            }
        return applyReplacements(source, replacements)
    }

    private fun containsFloatingExpression(
        expression: String,
        floatSymbols: Set<String>,
    ): Boolean {
        if (FLOAT_LITERAL.containsMatchIn(expression)) return true
        return IDENTIFIER.findAll(expression).any { it.value in floatSymbols }
    }

    private fun isFloatingType(type: String): Boolean {
        return type == "float" || type.startsWith("vec") || type.startsWith("mat")
    }

    private fun isIntegerType(type: String): Boolean {
        return type == "int" || type == "uint" || type.startsWith("ivec") || type.startsWith("uvec")
    }

    private fun injectOrbitCamera(source: String): Pair<String, Boolean> {
        if (source.contains("spApplyOrbitCamera(")) {
            return source to true
        }

        for ((positionName, targetName) in CAMERA_NAME_PAIRS) {
            val positionDeclaration = findVec3Initialization(source, positionName) ?: continue
            val targetDeclaration = findVec3Initialization(source, targetName) ?: continue
            if (findEnclosingBlockStart(source, positionDeclaration) !=
                findEnclosingBlockStart(source, targetDeclaration)
            ) {
                continue
            }

            val laterDeclaration = maxOf(positionDeclaration, targetDeclaration)
            val insertionPosition = findStatementEnd(source, laterDeclaration) ?: continue
            val indentation = indentationAt(source, laterDeclaration)
            val targetOverride = if (
                hasVec3DeclarationBefore(source, "sphereCenter", laterDeclaration)
            ) {
                "\n$indentation$targetName = sphereCenter;"
            } else {
                ""
            }
            val insertion = "$targetOverride\n$indentation" +
                "spApplyOrbitCamera($positionName, $targetName);"
            return source.substring(0, insertionPosition) + insertion +
                source.substring(insertionPosition) to true
        }

        for (functionName in CAMERA_FUNCTION_NAMES) {
            val patched = injectIntoCameraFunction(source, functionName)
            if (patched != null) return patched to true
        }

        return source to false
    }

    private fun injectIntoCameraFunction(source: String, functionName: String): String? {
        var searchFrom = 0
        while (searchFrom < source.length) {
            val nameIndex = source.indexOf(functionName, searchFrom)
            if (nameIndex < 0) return null
            if (!hasIdentifierBoundaries(source, nameIndex, functionName.length)) {
                searchFrom = nameIndex + functionName.length
                continue
            }

            val openParenthesis = skipWhitespace(source, nameIndex + functionName.length)
            if (openParenthesis >= source.length || source[openParenthesis] != '(') {
                searchFrom = nameIndex + functionName.length
                continue
            }
            val closeParenthesis = findMatching(source, openParenthesis, '(', ')') ?: return null
            val openBrace = skipWhitespace(source, closeParenthesis + 1)
            if (openBrace >= source.length || source[openBrace] != '{') {
                searchFrom = closeParenthesis + 1
                continue
            }

            val signature = source.substring(openParenthesis + 1, closeParenthesis)
            for ((positionName, targetName) in CAMERA_NAME_PAIRS) {
                if (containsVec3Parameter(signature, positionName) &&
                    containsVec3Parameter(signature, targetName)
                ) {
                    val indentation = indentationAt(source, openBrace) + "    "
                    val insertion = "\n$indentation" +
                        "spApplyOrbitCamera($positionName, $targetName);"
                    return source.substring(0, openBrace + 1) + insertion +
                        source.substring(openBrace + 1)
                }
            }
            searchFrom = closeParenthesis + 1
        }
        return null
    }

    private fun containsVec3Parameter(signature: String, name: String): Boolean {
        return Regex("\\b(?:in|out|inout)?\\s*vec3\\s+${Regex.escape(name)}\\b")
            .containsMatchIn(signature)
    }

    private fun findVec3Initialization(source: String, variableName: String): Int? {
        val regex = Regex("\\bvec3\\s+${Regex.escape(variableName)}\\s*=")
        return regex.find(source)?.range?.first
    }

    private fun hasVec3DeclarationBefore(
        source: String,
        variableName: String,
        position: Int,
    ): Boolean {
        val blockStart = findEnclosingBlockStart(source, position) ?: return false
        val range = source.substring(blockStart + 1, position)
        return Regex("\\bvec3\\s+${Regex.escape(variableName)}\\b").containsMatchIn(range)
    }

    private fun findEnclosingBlockStart(source: String, position: Int): Int? {
        val stack = mutableListOf<Int>()
        var lineComment = false
        var blockComment = false
        var index = 0
        val end = position.coerceAtMost(source.length)

        while (index < end) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when {
                lineComment -> {
                    if (current == '\n') lineComment = false
                }
                blockComment -> {
                    if (current == '*' && next == '/') {
                        blockComment = false
                        index++
                    }
                }
                current == '/' && next == '/' -> {
                    lineComment = true
                    index++
                }
                current == '/' && next == '*' -> {
                    blockComment = true
                    index++
                }
                current == '{' -> stack.add(index)
                current == '}' && stack.isNotEmpty() -> stack.removeLast()
            }
            index++
        }
        return stack.lastOrNull()
    }

    private fun findStatementEnd(source: String, start: Int): Int? {
        var parentheses = 0
        var brackets = 0
        var index = start
        while (index < source.length) {
            when (source[index]) {
                '(' -> parentheses++
                ')' -> parentheses--
                '[' -> brackets++
                ']' -> brackets--
                ';' -> if (parentheses == 0 && brackets == 0) return index + 1
            }
            index++
        }
        return null
    }

    private fun findMatching(
        source: String,
        openingIndex: Int,
        opening: Char,
        closing: Char,
    ): Int? {
        var depth = 0
        var lineComment = false
        var blockComment = false
        var index = openingIndex

        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when {
                lineComment -> if (current == '\n') lineComment = false
                blockComment -> if (current == '*' && next == '/') {
                    blockComment = false
                    index++
                }
                current == '/' && next == '/' -> {
                    lineComment = true
                    index++
                }
                current == '/' && next == '*' -> {
                    blockComment = true
                    index++
                }
                current == opening -> depth++
                current == closing -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    private fun findTopLevelStatements(source: String): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var depth = 0
        var statementStart = 0
        var lineComment = false
        var blockComment = false
        var index = 0

        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when {
                lineComment -> if (current == '\n') lineComment = false
                blockComment -> if (current == '*' && next == '/') {
                    blockComment = false
                    index++
                }
                current == '/' && next == '/' -> {
                    lineComment = true
                    index++
                }
                current == '/' && next == '*' -> {
                    blockComment = true
                    index++
                }
                depth == 0 && current == '#' && isPreprocessorStart(source, index) -> {
                    val lineEnd = source.indexOf('\n', index).let {
                        if (it < 0) source.length - 1 else it
                    }
                    index = lineEnd
                    statementStart = (lineEnd + 1).coerceAtMost(source.length)
                }
                current == '{' -> depth++
                current == '}' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    if (depth == 0) statementStart = index + 1
                }
                current == ';' && depth == 0 -> {
                    result += statementStart..index
                    statementStart = index + 1
                }
            }
            index++
        }
        return result
    }

    private fun isPreprocessorStart(source: String, index: Int): Boolean {
        var cursor = index - 1
        while (cursor >= 0 && source[cursor] != '\n') {
            if (!source[cursor].isWhitespace()) return false
            cursor--
        }
        return true
    }

    private fun splitArguments(arguments: String): List<String> {
        if (arguments.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var parentheses = 0
        var brackets = 0
        var braces = 0
        var start = 0

        arguments.forEachIndexed { index, character ->
            when (character) {
                '(' -> parentheses++
                ')' -> parentheses--
                '[' -> brackets++
                ']' -> brackets--
                '{' -> braces++
                '}' -> braces--
                ',' -> if (parentheses == 0 && brackets == 0 && braces == 0) {
                    result += arguments.substring(start, index)
                    start = index + 1
                }
            }
        }
        result += arguments.substring(start)
        return result
    }

    private fun hasIdentifierBoundaries(source: String, start: Int, length: Int): Boolean {
        val leftValid = start == 0 || !source[start - 1].isIdentifierCharacter()
        val end = start + length
        val rightValid = end >= source.length || !source[end].isIdentifierCharacter()
        return leftValid && rightValid
    }

    private fun Char.isIdentifierCharacter(): Boolean = isLetterOrDigit() || this == '_'

    private fun skipWhitespace(source: String, start: Int): Int {
        var index = start
        while (index < source.length && source[index].isWhitespace()) index++
        return index
    }

    private fun indentationAt(source: String, position: Int): String {
        val lineStart = source.lastIndexOf('\n', position).let { if (it < 0) 0 else it + 1 }
        var end = lineStart
        while (end < source.length && (source[end] == ' ' || source[end] == '\t')) end++
        return source.substring(lineStart, end)
    }

    private data class Replacement(
        val start: Int,
        val endExclusive: Int,
        val value: String,
    )

    private fun applyReplacements(source: String, replacements: List<Replacement>): String {
        if (replacements.isEmpty()) return source
        val result = StringBuilder(source)
        replacements.sortedByDescending { it.start }.forEach { replacement ->
            result.replace(replacement.start, replacement.endExclusive, replacement.value)
        }
        return result.toString()
    }

    private companion object {
        val MAIN_IMAGE_REGEX = Regex("\\bvoid\\s+mainImage\\s*\\(")
        val MAIN_FUNCTION_REGEX = Regex("\\bvoid\\s+main\\s*\\(")
        val IDENTIFIER_SPHERE_OFFSET = Regex("\\biSphereOffset\\b")
        val SAMPLER_1D_DECLARATION = Regex(
            "\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?(?:i|u)?sampler1D(?:Shadow)?\\s+([A-Za-z_]\\w*)",
        )
        val CONST_INT_DECLARATION = Regex(
            "\\bconst\\s+int\\s+([A-Za-z_]\\w*)\\s*=",
        )
        val INTEGER_MACRO_DECLARATION = Regex(
            "(?m)^\\s*#define\\s+([A-Za-z_]\\w*)\\s+[+-]?\\d+\\s*(?://.*)?$",
        )
        val INTEGER_SYMBOL_DECLARATION = Regex(
            "\\b(?:const\\s+)?(?:int|uint)\\s+([A-Za-z_]\\w*)\\b",
        )
        val MIXED_INT_FLOAT_MODULO = Regex(
            "\\b([A-Za-z_]\\w*)\\s*%\\s*" +
                "([+-]?(?:\\d+\\.\\d*|\\.\\d+|\\d+[eE][+-]?\\d+)[fF]?)",
        )
        val INTEGER_PRESERVING_CONSTRUCTORS = setOf(
            "float", "int", "uint", "ivec2", "ivec3", "ivec4",
            "uvec2", "uvec3", "uvec4",
        )
        val FLOATING_DECLARATION = Regex(
            "(?s)(\\b(?:const\\s+)?(?:float|vec[234]|mat[234])\\s+[A-Za-z_]\\w*\\s*=\\s*)([^;]+)(;)",
        )
        val SIMPLE_FLOAT_GLOBAL = Regex(
            "(?s)^(\\s*(?:(?://[^\\n]*(?:\\n|$))|(?:/\\*.*?\\*/\\s*))*)" +
                "(const\\s+)?(float|vec[234]|mat[234])\\s+([A-Za-z_]\\w*)" +
                "\\s*=\\s*(.+?)\\s*;\\s*$",
        )
        val DYNAMIC_GLOBAL_IDENTIFIER = Regex(
            "\\b(?:fGlobalTime|iGlobalTime|iTime|iTimeDelta|iFrame|iMouse|iDate|" +
                "v2Resolution|iResolution|v2Mouse|v4Mouse|gl_FragCoord|texture|spTexture1D)\\b",
        )
        val INTEGER_LITERAL = Regex("[+-]?\\d+")
        val FLOAT_BUILTIN_NAMES = listOf(
            "clamp",
            "mix",
            "smoothstep",
            "step",
            "min",
            "max",
            "mod",
            "pow",
        )
        val FLOATING_SYMBOL_DECLARATION = Regex(
            "\\b(?:const\\s+)?(?:(?:lowp|mediump|highp)\\s+)?" +
                "(?:float|vec[234]|mat[234])\\s+([A-Za-z_]\\w*)",
        )
        val FUNCTION_HEADER = Regex(
            "\\b(void|float|vec[234]|mat[234]|int|uint|ivec[234]|uvec[234]|bool|bvec[234])" +
                "\\s+([A-Za-z_]\\w*)\\s*\\(",
        )
        val PARAMETER_TYPE = Regex(
            "\\b(float|vec[234]|mat[234]|int|uint|ivec[234]|uvec[234]|bool|bvec[234]|" +
                "sampler\\w*)\\b",
        )
        val FLOATING_ASSIGNMENT = Regex(
            """(?s)(\b(?:(float|vec[234]|mat[234]|int|uint|ivec[234]|uvec[234]|bool|bvec[234])\s+)?([A-Za-z_]\w*)(?:\s*\.[A-Za-z_]\w*)?(?:\s*\[[^\]]+])?\s*(?:\+=|-=|\*=|/=|(?<![=!<>])=(?!=))\s*)([^;]+)(;)""",
        )


        val RETURN_EXPRESSION = Regex("(?s)\\breturn\\s+([^;]+);")
        val IDENTIFIER = Regex("\\b[A-Za-z_]\\w*\\b")
        val FLOAT_LITERAL = Regex(
            "(?<![A-Za-z0-9_])(?:\\d+\\.\\d*|\\.\\d+|\\d+[eE][+-]?\\d+)[fF]?",
        )
        val FLOATING_CONSTRUCTORS = listOf(
            "vec2", "vec3", "vec4", "mat2", "mat3", "mat4",
        )
        val FLOAT_UNARY_BUILTINS = listOf(
            "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh",
            "abs", "floor", "ceil", "fract", "sign", "sqrt", "inversesqrt", "exp", "exp2",
            "log", "log2", "radians", "degrees", "length", "normalize", "dFdx", "dFdy", "fwidth",
        )
        val CAMERA_NAME_PAIRS = listOf(
            "cameraPosition" to "cameraTarget",
            "cameraPosition" to "target",
            "cameraPos" to "cameraTarget",
            "cameraPos" to "target",
            "camPosition" to "camTarget",
            "camPos" to "camTarget",
            "cameraOrigin" to "cameraTarget",
            "cameraOrigin" to "target",
            "rayOrigin" to "cameraTarget",
            "rayOrigin" to "target",
            "eye" to "target",
            "eye" to "center",
            "eyePos" to "lookAt",
            "from" to "to",
            "from" to "target",
            "origin" to "target",
            "ro" to "ta",
            "ro" to "target",
        )
        val CAMERA_FUNCTION_NAMES = listOf(
            "getStaticCameraRay",
            "getCameraRay",
            "buildCameraRay",
            "cameraRay",
            "getRayDirection",
            "makeCameraRay",
        )

        val C_STYLE_ARRAY_INITIALIZER = Regex(
            "\\b([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*\\[\\s*([^]\\n]+)\\s*]\\s*=\\s*\\{",
        )

        val LEGACY_SAMPLER_BINDINGS = linkedMapOf(
            "texFFT" to 0,
            "texFFTSmoothed" to 1,
            "texFFTIntegrated" to 2,
            "texPreviousFrame" to 3,
            "texChecker" to 0,
            "texNoise" to 0,
            "texTex1" to 0,
            "texTex2" to 1,
            "texTex3" to 2,
            "texTex4" to 3,
        )

        const val LEGACY_TEXTURE_HELPERS = """
vec4 spTexture1D(sampler2D samplerValue, float coordinate)
{
    return texture(samplerValue, vec2(coordinate, 0.5));
}

vec4 spTexture1D(sampler2D samplerValue, float coordinate, float bias)
{
    return texture(samplerValue, vec2(coordinate, 0.5), bias);
}

vec4 spTexture1DLod(sampler2D samplerValue, float coordinate, float lod)
{
    return textureLod(samplerValue, vec2(coordinate, 0.5), lod);
}

vec4 spTexture1DProj(sampler2D samplerValue, vec2 coordinate)
{
    return textureProj(samplerValue, vec3(coordinate.x, 0.5 * coordinate.y, coordinate.y));
}

vec4 spTexture1DProjLod(sampler2D samplerValue, vec2 coordinate, float lod)
{
    return textureProjLod(samplerValue, vec3(coordinate.x, 0.5 * coordinate.y, coordinate.y), lod);
}

vec4 spTexelFetch1D(sampler2D samplerValue, int coordinate, int lod)
{
    return texelFetch(samplerValue, ivec2(coordinate, 0), lod);
}
"""

        const val SCENE_HELPERS = """
vec2 spTransformFragCoord(vec2 coordinate)
{
    vec2 centered = (coordinate - 0.5 * iResolution.xy) / iResolution.y;
    centered -= spScenePan;
    centered *= max(spSceneZoom, 0.001);
    float angle = -spSceneRotation.x;
    float c = cos(angle);
    float s = sin(angle);
    centered = mat2(c, -s, s, c) * centered;
    return centered * iResolution.y + 0.5 * iResolution.xy;
}

void spApplyOrbitCamera(inout vec3 cameraPosition, inout vec3 cameraTarget)
{
    vec3 offset = cameraPosition - cameraTarget;
    float distanceToTarget = max(length(offset), 0.001);
    float horizontalLength = max(length(offset.xz), 0.000001);
    float baseYaw = atan(offset.x, offset.z);
    float basePitch = atan(offset.y, horizontalLength);
    float yaw = baseYaw + spSceneRotation.x;
    float pitch = clamp(basePitch + spSceneRotation.y, radians(-89.0), radians(89.0));

    float orbitDistance = distanceToTarget * max(spSceneZoom, 0.001);
    vec3 orbitDirection = vec3(
        sin(yaw) * cos(pitch),
        sin(pitch),
        cos(yaw) * cos(pitch)
    );

    vec3 forward = normalize(-orbitDirection);
    vec3 right = cross(forward, vec3(0.0, 1.0, 0.0));
    if (dot(right, right) < 0.000001) {
        right = vec3(1.0, 0.0, 0.0);
    } else {
        right = normalize(right);
    }
    vec3 up = normalize(cross(right, forward));

    cameraTarget += -right * spScenePan.x * orbitDistance * 2.0;
    cameraTarget += -up * spScenePan.y * orbitDistance * 2.0;
    cameraPosition = cameraTarget + orbitDirection * orbitDistance;
}
"""
    }
}
