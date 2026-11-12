/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.app.Application
import com.corrodinggames.rts.game.b.b as EngineMap
import com.corrodinggames.rts.game.b.g as MapLayer
import com.corrodinggames.rts.game.b.i as MapTile
import com.corrodinggames.rts.game.units.ce as CoreUnit
import com.corrodinggames.rts.game.units.cj as CoreUnitType
import com.corrodinggames.rts.game.units.e.b as BuilderUnit
import com.corrodinggames.rts.game.units.e.i as HovercraftUnit
import com.corrodinggames.rts.gameFramework.m.e as EngineImage
import io.github.rwpp.android.impl.GameEngine
import io.github.rwpp.android.impl.GameImpl
import javassist.ClassClassPath
import javassist.ClassPool
import javassist.CtClass
import javassist.bytecode.Opcode
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class AndroidCoreChecksumTest {
    @Test
    fun menuWaterChangesRealUnitChecksumsWithoutChangingRegistrationChecksum() {
        val singleton = GameEngine::class.java.getDeclaredField("ak").apply { isAccessible = true }
        val previousEngine = singleton.get(null)
        val engine = allocate(Class.forName("com.corrodinggames.rts.game.i").asSubclass(GameEngine::class.java))
        engine.bN = allocate(com.corrodinggames.rts.gameFramework.SettingsEngine::class.java)
        val cached = engine.javaClass.getDeclaredField("c").apply { isAccessible = true }
        singleton.set(null, engine)
        val previousTemplates = CoreUnit.bH
        val templateImages = linkedMapOf<Field, Any?>()
        try {
            templateImageFields().forEach { templateImages[it] = it.get(null) }
            // 原版构造器读取贴图尺寸；占位对象不涉及单位属性与校验算法。
            templateImages.keys.forEach {
                it.set(null, if (it.type.isArray) Array(10) { EngineImage() } else EngineImage())
            }
            // 真实内置模板；不加载贴图、音效或菜单，也不运行 GameImpl 的平台服务初始化。
            CoreUnit.bH = HashMap<CoreUnitType, CoreUnit>().apply {
                CoreUnitType.values().forEach { put(it, it.a(true)) }
            }
            val game = allocate(GameImpl::class.java)
            val builder = CoreUnit.bH[CoreUnitType.valueOf("builder")] as BuilderUnit
            val hovercraft = CoreUnit.bH[CoreUnitType.valueOf("hovercraft")] as HovercraftUnit
            assertEquals(291396218, builder.bf())
            assertEquals(-1386332285, hovercraft.bf())
            val initializedChecksum = CoreUnit.bt()
            assertEquals(678359601, initializedChecksum, "Core checksum before the menu map is loaded")
            // 与原版 i 初始化相同，在菜单地图加载前捕获核心校验和。
            cached.setInt(engine, initializedChecksum)

            engine.bI = waterMap()
            assertTrue(builder.cn())
            assertTrue(hovercraft.cn())
            assertEquals(266669688, builder.bf())
            assertEquals(-1357435015, hovercraft.bf())
            assertEquals(1130203129, CoreUnit.bt(), "The screenshot checksum comes from the menu terrain")
            assertEquals(678359601, game.getUnitsChecksum(), "Direct admission must use the original init checksum")

            game.refreshHandshakeChecksumCache()
            assertEquals(678359601, engine.r(), "Reload must preserve the checksum sent in packet 110")
            assertEquals(678359601, cached.getInt(engine))
            engine.bI = null
            assertEquals(678359601, game.getUnitsChecksum())
        } finally {
            templateImages.forEach { (field, previous) -> field.set(null, previous) }
            CoreUnit.bH = previousTemplates
            if (singleton.get(null) === engine) singleton.set(null, previousEngine)
        }
    }

    /** 找到内置枚举工厂实际构造的类，只为其静态贴图提供对象，不改写引擎方法。 */
    private fun templateImageFields(): Set<Field> {
        val pool = ClassPool(true).apply { insertClassPath(ClassClassPath(CoreUnit::class.java)) }
        return buildSet {
            CoreUnitType.values().forEach { type ->
                val factory = pool.get(type.javaClass.name).getDeclaredMethod("a", arrayOf(CtClass.booleanType))
                val code = factory.methodInfo.codeAttribute.iterator()
                while (code.hasNext()) {
                    val position = code.next()
                    if (code.byteAt(position) != Opcode.NEW) continue
                    val name = factory.methodInfo.constPool.getClassInfo(code.u16bitAt(position + 1))
                    var implementation: Class<*>? = Class.forName(name)
                    while (implementation != null && CoreUnit::class.java.isAssignableFrom(implementation)) {
                        implementation.declaredFields.filterTo(this) {
                            Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) &&
                                (it.type == EngineImage::class.java || it.type.componentType == EngineImage::class.java)
                        }
                        implementation = implementation.superclass
                    }
                }
            }
        }.onEach { it.isAccessible = true }
    }

    /** 一格真实水地图，让原版 cn() 查询模板坐标 (0, 0) 时返回水面。 */
    private fun waterMap(): EngineMap {
        val tile = allocate(MapTile::class.java).apply { e = true }
        val map = allocate(EngineMap::class.java).apply {
            r = 1f
            s = 1f
            D = 1
            E = 1
            C = arrayOf(tile)
        }
        map.u = allocate(MapLayer::class.java).apply {
            i = map
            n = 1
            o = 1
            q = shortArrayOf(0)
        }
        return map
    }

    // 编译期使用 Android SDK；宿主 JVM 的 Unsafe 只用于跳过未涉及本测试的初始化。
    private val unsafeClass = Class.forName("sun.misc.Unsafe")
    private val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    private val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
    private fun <T : Any> allocate(type: Class<T>): T = type.cast(allocate.invoke(unsafe, type))
}
