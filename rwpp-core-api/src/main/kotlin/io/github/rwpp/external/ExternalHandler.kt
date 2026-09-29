/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.external

import io.github.rwpp.core.Initialization
import javassist.ClassPath
import org.koin.core.component.KoinComponent
import java.io.File

interface ExternalHandler : KoinComponent, Initialization {
    //val mainLoader: LibClassLoader

    fun getAllExtensions(update: Boolean = false): Result<List<Extension>>

    @Suppress("unused")
    fun getExtensionById(id: String): Extension? {
        return getAllExtensions().getOrThrow().firstOrNull { ext -> ext.config.id == id }
    }

    fun canEnable(extension: Extension): Boolean

    fun enableResource(resource: Extension?)

    fun getUsingResource(): Extension?

    /**
     * 重建 `resource_generated/`：原版基线 + 当前启用的覆盖层
     *（扩展系统的 `.rwres` 资源包 + 主题美术包的 `game/` 目录，后者最后叠加）。
     * 所有覆盖层均不存在时删除输出目录（资源重定向随之关闭）。
     * 实现必须先把内容构建到临时目录再原子换名，避免引擎读到半成品。
     */
    fun rebuildResourceOverlay()

    fun openFileChooser(
        onProgress: ((FileChooseProgress) -> Unit)? = null,
        onChooseFile: (File) -> Unit
    )

  //  fun loadExtensionClass(extension: Extension): ClassLoader?

    fun loadJarToSystemClassPath(jar: File)

    fun newExtension(
        isEnabled: Boolean,
        isZip: Boolean,
        extensionFile: File,
        config: ExtensionConfig
    ): Extension
}
