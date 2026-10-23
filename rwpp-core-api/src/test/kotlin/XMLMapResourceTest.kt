/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.map.GameMap
import io.github.rwpp.game.map.XMLMap
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XMLMapResourceTest {
    @Test fun invalidMapTransformationReleasesItsInputFile() {
        var closed = false
        val stream = object : ByteArrayInputStream("<map width=\"invalid\" height=\"1\" tilewidth=\"20\" tileheight=\"20\"/>".toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        val map = Proxy.newProxyInstance(GameMap::class.java.classLoader, arrayOf(GameMap::class.java)) { _, method, _ ->
            check(method.name == "openInputStream")
            stream
        } as GameMap
        assertFailsWith<NumberFormatException> { XMLMap(map) }
        assertTrue(closed, "失败后必须释放输入文件，允许用户替换地图后重试")
    }
}
