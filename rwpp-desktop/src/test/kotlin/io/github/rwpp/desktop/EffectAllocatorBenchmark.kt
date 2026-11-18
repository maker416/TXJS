/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import com.corrodinggames.rts.gameFramework.d.c as EffectEngine
import com.corrodinggames.rts.gameFramework.d.e as Effect
import com.corrodinggames.rts.gameFramework.d.h as Priority
import io.github.rwpp.desktop.impl.inject.EffectLimitInject
import java.util.Locale
import kotlin.system.measureNanoTime

/** 手动基准，使用实际已注入的桌面游戏 JAR，不依赖图形设备。见 docs/in-game-performance.md。 */
object EffectAllocatorBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        val scenario = args.single()
        require(scenario == "dense" || scenario == "holes") { "Use dense or holes" }
        val engine = EffectEngine()
        val constructor = Effect::class.java.getDeclaredConstructor(EffectEngine::class.java).apply { isAccessible = true }
        val pool = Array(4096) { constructor.newInstance(engine) }
        EffectEngine.f = pool
        EffectEngine.g = 0
        EffectLimitInject.configure(Int.MAX_VALUE)
        println("# hook=${EffectLimitInject::class.java.protectionDomain.codeSource.location} JVM=${Runtime.version()}")
        for (slot in pool) check(engine.a(Priority.a) === slot) { "Expected the injected allocator" }

        val stride = if (scenario == "holes") 2 else 1
        fun burst(): Long {
            if (stride == 1) engine.a(false) else for (i in pool.indices step 2) pool[i].b()
            val elapsed = measureNanoTime {
                for (i in pool.indices step stride) check(engine.a(Priority.a) === pool[i]) { "Wrong slot: $i" }
            }
            check(engine.a == pool.size && EffectEngine.g == pool.size && EffectEngine.f === pool)
            return elapsed
        }
        repeat(80) { burst() }
        repeat(31) { sample ->
            var total = 0L
            repeat(4) { total += burst() }
            System.out.printf(Locale.ROOT, "%s,%d,%.6f%n", scenario, sample + 1, total / 4_000_000.0)
        }
    }
}
