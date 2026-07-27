package com.goodwin.shaderplayer.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderSourceAdapterTest {
    private val adapter = ShaderSourceAdapter()

    @Test
    fun `mainImage gets GLES entry point`() {
        val result = adapter.adapt(
            "void mainImage(out vec4 c, in vec2 p) { c = vec4(p, 0.0, 1.0); }",
            playerControlsEnabled = false,
        )

        assertTrue(result.source.startsWith("#version 300 es"))
        assertTrue(result.source.contains("void main()"))
        assertTrue(result.source.contains("mainImage(spFragColor, gl_FragCoord.xy)"))
    }

    @Test
    fun `player controls can be disabled without source transform`() {
        val result = adapter.adapt(
            "void mainImage(out vec4 c, in vec2 p) { c = vec4(1.0); }",
            playerControlsEnabled = false,
        )

        assertFalse(result.source.contains("spTransformFragCoord"))
        assertFalse(result.source.contains("spApplyOrbitCamera"))
    }

    @Test
    fun `camera pair gets orbit hook`() {
        val source = """
            vec3 render(vec2 uv) {
                vec3 cameraPosition = vec3(0.0, 3.0, 6.0);
                vec3 cameraTarget = vec3(0.0);
                return cameraPosition - cameraTarget;
            }
            void mainImage(out vec4 c, in vec2 p) { c = vec4(render(p), 1.0); }
        """.trimIndent()

        val result = adapter.adapt(source, playerControlsEnabled = true)

        assertTrue(result.cameraPatched)
        assertTrue(result.source.contains("spApplyOrbitCamera(cameraPosition, cameraTarget);"))
    }
}
