/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl.inject

import com.corrodinggames.rts.gameFramework.e.c as ExternalFileLoader
import com.corrodinggames.rts.gameFramework.e.d as PathWrappedFileLoader
import com.corrodinggames.rts.gameFramework.e.e as CombinedFileLoader
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode

/**
 * 覆盖原版 FileLoader 工厂方法 [com.corrodinggames.rts.gameFramework.e.a.a]，
 * 返回一个组合后端 [CombinedFileLoader]（原版 e.e），同时挂载：
 * - primary：外部公共目录 /sdcard/rustedWarfare/（向后兼容用户已导入的模组、地图等）
 * - secondary：应用私有目录 getExternalFilesDir/（存放网络同步的房主模组，
 *   非 root 设备上文件管理器无法访问，卸载 App 时随应用一起删除）
 *
 * 引擎的模组扫描（i.a → e.a.h → e.a.b.b）在路径不含 tag 时会**合并** primary 与
 * secondary 两个后端的目录列表（已通过字节码验证 e.e.b(String, boolean) 的合并行为），
 * 因此放在私有目录的网络模组会被引擎一并扫描加载。
 *
 * tag 字符串使用原版约定 [EXTERNAL_TAG]/[INTERNAL_TAG]，与原版 e.a.a(int storageType)
 * 在 storageType=2（external 主 + internal 副）模式下产生的字面量一致，保证原版路径
 * 解析、迁移、日志逻辑正常工作。
 */
@InjectClass(com.corrodinggames.rts.gameFramework.e.a::class)
object FileLoaderInject {
    private const val EXTERNAL_TAG = "[EXTERNAL-PATH]/"
    private const val INTERNAL_TAG = "[INTERNAL-PATH]/"

    @Inject("a", InjectMode.Override)
    fun getFileLoader(storageType: Int): ExternalFileLoader? {
        val ctx = appKoin.get<AppContext>()
        // externalStoragePath("") → /sdcard/rustedWarfare/（注意末尾带 rustedWarfare 根）
        // internalStoragePath("") → /Android/data/<pkg>/files/
        val external = PathWrappedFileLoader(ctx.externalStoragePath(""), "external")
        val internal = PathWrappedFileLoader(ctx.internalStoragePath(""), "internal")
        // 与原版 e.a.a(int) 工厂的收尾行为对齐：secondary 后端禁用 assets 回退
        // （e.c.d，反编译日志串 "fileExists: false with disableAssets"）。
        // 缺了这一步时，列举 builtin_mods 这类相对路径会让两个后端都回退命中
        // assets/builtin_mods，合并列举不去重，同一内置模组（如 Mega Builders）
        // 会以 [EXTERNAL-PATH]/ 与 [INTERNAL-PATH]/ 两个不同 tag 各注册一次，
        // 在模组管理器中显示为两个。该标志只影响 assets 回退，不影响 secondary
        // 根目录下真实文件（网络同步模组）的列举与读写。
        internal.d = true
        return CombinedFileLoader(external, EXTERNAL_TAG, internal, INTERNAL_TAG)
    }
}
