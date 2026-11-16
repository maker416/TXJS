/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.app.Application
import com.corrodinggames.rts.gameFramework.aj
import com.corrodinggames.rts.gameFramework.e.a as FileLoader
import com.corrodinggames.rts.gameFramework.e.d as PathWrappedFileLoader
import com.corrodinggames.rts.gameFramework.e.e as CombinedFileLoader
import io.github.rwpp.game.map.SavedGameFile
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class SavedGameStorageTest {
    @Test fun originalSaveLoaderDistinguishesPrivateAndPublicSavesWithTheSameName() {
        val root = Files.createTempDirectory("rwjs-save-storage-").toFile()
        val external = File(root, "external")
        val internal = File(root, "internal")
        val previous = FileLoader.b
        try {
            FileLoader.b = CombinedFileLoader(
                PathWrappedFileLoader(external.path, "external"), "[EXTERNAL-PATH]/",
                PathWrappedFileLoader(internal.path, "internal"), "[INTERNAL-PATH]/",
            )
            val name = "same.rwsave"
            val publicFile = File(external, "saves/$name")
            val privateFile = File(internal, "saves/$name")
            val publicMap = SavedGameFile(publicFile).toGameMap(0)
            val privateMap = SavedGameFile(privateFile, "[INTERNAL-PATH]/$name").toGameMap(1)
            assertEquals(publicFile.canonicalFile,
                aj.a(publicMap.mapName + publicMap.getMapSuffix(), false).canonicalFile)
            assertEquals(privateFile.canonicalFile,
                aj.a(privateMap.mapName + privateMap.getMapSuffix(), false).canonicalFile)
        } finally {
            FileLoader.b = previous
            root.deleteRecursively()
        }
    }
}
