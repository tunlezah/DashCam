package com.tunlezah.dashcam.recording.engine

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private val FULL_QUAD_POS = floatArrayOf(
    -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f,
)
private val FULL_QUAD_TEX = floatArrayOf(
    0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f,
)

private fun FloatArray.toBuffer(): FloatBuffer =
    ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .put(this).apply { position(0) }

private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    check(status[0] != 0) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
    return shader
}

private fun linkProgram(vertex: String, fragment: String): Int {
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, compileShader(GLES20.GL_VERTEX_SHADER, vertex))
    GLES20.glAttachShader(program, compileShader(GLES20.GL_FRAGMENT_SHADER, fragment))
    GLES20.glLinkProgram(program)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    check(status[0] != 0) { "program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
    return program
}

/**
 * Draws the camera's external OES texture as a full-target quad. The combined
 * texture-coordinate matrix (SurfaceTexture transform × rotation × crop) is
 * computed on the CPU per frame by [FrameGeometry].
 */
class OesTextureProgram {
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private val posBuffer = FULL_QUAD_POS.toBuffer()
    private val texBuffer = FULL_QUAD_TEX.toBuffer()

    fun init() {
        program = linkProgram(
            """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
            """.trimIndent(),
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
            """.trimIndent(),
        )
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
    }

    fun draw(oesTextureId: Int, texMatrix: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, posBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
    }
}

/**
 * Draws the overlay strip (a Canvas-rendered bitmap) as a translucent quad at
 * a given normalized rectangle of the output. The bitmap is uploaded only when
 * its version changes (≤1 Hz), so the steady-state cost is one blended quad.
 */
class OverlayQuadProgram {
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var textureId = 0
    private var uploadedVersion = -1L
    private var texWidth = 0
    private var texHeight = 0
    private val texBuffer = FULL_QUAD_TEX.toBuffer()

    fun init() {
        program = linkProgram(
            """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord.xy;
            }
            """.trimIndent(),
            """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
            """.trimIndent(),
        )
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun maybeUpload(bitmap: Bitmap, version: Long) {
        if (version == uploadedVersion) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        if (bitmap.width != texWidth || bitmap.height != texHeight) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            texWidth = bitmap.width
            texHeight = bitmap.height
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        uploadedVersion = version
    }

    /**
     * @param rect normalized device coords of the quad: left, bottom, right, top in [-1,1]
     */
    fun draw(rect: FloatArray) {
        if (uploadedVersion < 0) return
        val pos = floatArrayOf(
            rect[0], rect[1], rect[2], rect[1], rect[0], rect[3], rect[2], rect[3],
        ).toBuffer()
        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        // Bitmap rows are top-down; flip T so text is upright.
        val flipped = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f).toBuffer()
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, flipped)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() {
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        textureId = 0
        program = 0
        uploadedVersion = -1
    }
}
