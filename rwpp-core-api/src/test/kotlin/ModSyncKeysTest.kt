/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync

import io.github.rwpp.net.RoomDescription
import io.github.rwpp.net.RoomJoinType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ModSyncKeysTest {

    @Test
    fun listedRoomPrefersUuidAlias() {
        val desc = RoomDescription(
            uuid = "e72d379e-b489-480e-b356-cb3251c0c2b6",
            roomJoinType = RoomJoinType.SHORT,
            netWorkAddress = "Q77182",
        )
        assertEquals(
            listOf("sid:e72d379e-b489-480e-b356-cb3251c0c2b6", "code:Q77182"),
            forRoomDescription(desc)
        )
    }

    @Test
    fun shortCodeIsNormalizedToUpperCase() {
        val desc = RoomDescription(
            uuid = "",
            roomJoinType = RoomJoinType.SHORT,
            netWorkAddress = "q77182",
        )
        assertEquals(listOf("code:Q77182"), forRoomDescription(desc))
    }

    @Test
    fun directIpRoomYieldsNoCodeKey() {
        val desc = RoomDescription(
            uuid = "cba477df-a190-4278-87ff-b29b9863ff4a",
            roomJoinType = RoomJoinType.IP,
            netWorkAddress = "54.215.144.145",
            port = 5130,
        )
        assertEquals(listOf("sid:cba477df-a190-4278-87ff-b29b9863ff4a"), forRoomDescription(desc))
    }

    @Test
    fun directIpRoomWithoutUuidYieldsEmpty() {
        val desc = RoomDescription(
            uuid = "",
            roomJoinType = RoomJoinType.IP,
            customIp = "127.0.0.1:5123",
        )
        assertTrue(forRoomDescription(desc).isEmpty())
    }

    @Test
    fun legacyGetAddressYieldsNoCodeKey() {
        val desc = RoomDescription(
            uuid = "",
            roomId = 42,
            uuid2 = "some|relay|uuid",
        )
        assertTrue(forRoomDescription(desc).isEmpty())
    }

    @Test
    fun addressWithShortCode() {
        assertEquals(listOf("code:Q77182"), forAddress("Q77182"))
        assertEquals(listOf("code:R1024"), forAddress("r1024"))
    }

    @Test
    fun addressWithShortCodeAndPort() {
        assertEquals(listOf("code:Q77182"), forAddress("q77182:5123"))
    }

    @Test
    fun addressWithDirectIpYieldsEmpty() {
        assertTrue(forAddress("54.215.144.145:5130").isEmpty())
        assertTrue(forAddress("192.168.1.1").isEmpty())
    }

    @Test
    fun addressIsTrimmed() {
        assertEquals(listOf("code:R123"), forAddress("  R123  "))
    }

    @Test
    fun fingerprintIsOrderIndependent() {
        val dir = Files.createTempDirectory("msk-fp").toFile()
        val a = File(dir, "a.rwmod").apply { writeBytes(byteArrayOf(1)) }
        val b = File(dir, "b.rwmod").apply { writeBytes(byteArrayOf(2)) }

        val fp1 = fingerprint(listOf("modA" to a, "modB" to b))
        val fp2 = fingerprint(listOf("modB" to b, "modA" to a))
        assertEquals(fp1, fp2)

        dir.deleteRecursively()
    }

    @Test
    fun fingerprintChangesWithFileContentMetadata() {
        val dir = Files.createTempDirectory("msk-fp2").toFile()
        val a = File(dir, "a.rwmod").apply { writeBytes(byteArrayOf(1)) }

        val before = fingerprint(listOf("modA" to a))
        a.setLastModified(a.lastModified() + 10_000)
        val afterMtime = fingerprint(listOf("modA" to a))
        assertNotEquals(before, afterMtime)

        a.writeBytes(byteArrayOf(1, 2))
        val afterSize = fingerprint(listOf("modA" to a))
        assertNotEquals(afterMtime, afterSize)

        dir.deleteRecursively()
    }

    @Test
    fun fingerprintTracksDirectoryContentsRecursively() {
        val dir = Files.createTempDirectory("msk-fp3").toFile()
        val modDir = File(dir, "mod").apply { mkdirs() }
        val nested = File(modDir, "units").apply { mkdirs() }
        val unit = File(nested, "tank.ini").apply { writeText("[core]\nname=tank") }

        val before = fingerprint(listOf("modA" to modDir))
        unit.setLastModified(unit.lastModified() + 10_000)
        val afterEdit = fingerprint(listOf("modA" to modDir))
        assertNotEquals(before, afterEdit)

        dir.deleteRecursively()
    }
}
