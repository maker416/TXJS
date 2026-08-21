/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.graphics

import GameCanvas
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

object GL {
    lateinit var gameCanvas: GameCanvas

    fun createOrthoMatrix(left: Float, right: Float, bottom: Float, top: Float, near: Float, far: Float): FloatArray {
        val m = FloatArray(16)
        val rL = 1f / (right - left)
        val tB = 1f / (top - bottom)
        val fN = 1f / (far - near)

        m[0] = 2f * rL
        m[5] = 2f * tB
        m[10] = -2f * fN
        m[12] = -(right + left) * rL
        m[13] = -(top + bottom) * tB
        m[14] = -(far + near) * fN
        m[15] = 1f
        return m
    }

    fun createCircleBuffer(radius: Float, segments: Int = 36): FloatBuffer {
        val vertices = FloatArray((segments + 2) * 2)
        vertices[0] = 0f
        vertices[1] = 0f

        for (i in 0..segments) {
            val angle = Math.PI * 2 * i / segments
            vertices[(i + 1) * 2] = (cos(angle) * radius).toFloat()
            vertices[(i + 1) * 2 + 1] = (sin(angle) * radius).toFloat()
        }

        return ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices).also { it.position(0) }
    }
}
