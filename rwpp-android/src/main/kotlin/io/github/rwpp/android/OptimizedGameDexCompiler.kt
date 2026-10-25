/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import com.android.dx.cf.direct.DirectClassFile
import com.android.dx.cf.direct.StdAttributeFactory
import com.android.dx.dex.DexOptions
import com.android.dx.dex.cf.CfOptions
import com.android.dx.dex.cf.CfTranslator
import com.android.dx.dex.file.DexFile
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** 保留现有 DX 后端和注入逻辑，显式启用包装库遗漏的字节码优化。 */
object OptimizedGameDexCompiler {
    fun compile(inputJar: File, outputDex: File) {
        val dexOptions = DexOptions()
        val dex = DexFile(dexOptions)
        val classOptions = CfOptions().apply { optimize = true }
        ZipFile(inputJar).use { jar ->
            for (entry in jar.entries().asSequence()) {
                if (!entry.name.endsWith(".class")) continue
                val bytes = jar.getInputStream(entry).use { it.readBytes() }
                val clazz = DirectClassFile(bytes, entry.name, true).apply {
                    setAttributeFactory(StdAttributeFactory.THE_ONE)
                }
                dex.add(CfTranslator.translate(clazz, bytes, classOptions, dexOptions, dex))
            }
        }

        if (outputDex.exists() && !outputDex.delete()) {
            throw IOException("Cannot replace generated DEX: $outputDex")
        }
        try {
            outputDex.outputStream().use { output ->
                // 与原包装库一致：先打开写描述符，再标记只读，满足 Android 14 动态代码加载要求。
                if (!outputDex.setReadOnly()) throw IOException("Cannot mark generated DEX read-only: $outputDex")
                dex.writeTo(output, null, false)
            }
        } catch (error: Throwable) {
            // 编译或写入失败不得留下会被下一次启动误认为有效的 classes.dex。
            outputDex.delete()
            throw error
        }
    }
}
