/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import io.github.rwpp.inject.runtime.SelectOptimizationBytecode
import io.github.rwpp.tools.heap.agent.HeapAgent
import javassist.ClassPool
import javassist.LoaderClassPath
import java.io.ByteArrayInputStream
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain

/** 只在显式对比客户端优化时修改独立 JVM；默认测量仍使用原版核心。 */
internal object EngineSelectOptimization {
    private const val SELECT = "com/corrodinggames/rts/game/units/custom/logicBooleans/LogicString\$Select"
    @Volatile private var applied = false
    @Volatile private var failure: Throwable? = null

    fun install() {
        HeapAgent.addTransformer(object : ClassFileTransformer {
            override fun transform(
                loader: ClassLoader?, name: String?, redefined: Class<*>?,
                domain: ProtectionDomain?, bytes: ByteArray?,
            ): ByteArray? {
                if (name != SELECT || bytes == null) return null
                try {
                    val pool = ClassPool(false).apply {
                        appendSystemPath()
                        if (loader != null) appendClassPath(LoaderClassPath(loader))
                    }
                    val clazz = pool.makeClass(ByteArrayInputStream(bytes))
                    return try {
                        SelectOptimizationBytecode.patch(clazz)
                        clazz.toBytecode().also { applied = true }
                    } finally {
                        clazz.detach()
                    }
                } catch (error: Throwable) {
                    // Instrumentation 会忽略 transformer 异常；初始化后必须显式检查，不能误报已优化。
                    failure = error
                    return null
                }
            }
        })
    }

    fun verifyApplied() {
        failure?.let { throw IllegalStateException("客户端 Select 解析优化安装失败", it) }
        check(applied) { "客户端 Select 解析优化未应用，停止对比测量" }
    }
}
