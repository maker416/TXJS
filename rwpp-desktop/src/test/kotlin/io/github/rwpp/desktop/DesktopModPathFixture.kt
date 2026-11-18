/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import io.github.rwpp.AppContext
import io.github.rwpp.ui.UI
import javassist.ClassPool
import javassist.Loader
import java.io.File
import java.util.function.Consumer

/** 每次从未初始化的生产路径 lazy 开始；不会读写其他测试的 appKoin 或缓存路径。 */
internal fun <T> runDesktopModPathFixture(
    fixture: Class<out Consumer<T>>, input: T, vararg extraModules: Class<*>,
) {
    // 子加载器只包含夹具、core-api、core 和显式要求的模块。窗口用例共用父加载器的桌面窗口、Compose 和 Skiko。
    val pool = ClassPool(false)
    (listOf(fixture, AppContext::class.java, UI::class.java) + extraModules)
        .map { it.protectionDomain.codeSource.location }.distinct()
        .forEach { pool.appendClassPath(File(it.toURI()).path) }
    val loader = Loader(fixture.classLoader, pool)
    val entry = loader.loadClass(fixture.name).asSubclass(Consumer::class.java)
    @Suppress("UNCHECKED_CAST") // 只有 JDK 的 Consumer 和它的输入跨越加载器边界。
    val isolated = entry.getConstructor().newInstance() as Consumer<T>
    isolated.accept(input)
}
