/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

@file:JvmName("PlatformExpansionFileChanges")

package io.github.rwpp.content

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.FileTime

internal actual fun readExpansionFileChange(file: File): ExpansionFileChange? = try {
    if (Platform.isWindows()) WindowsFileChanges.read(file) else unixFileChangeToken(file)
} catch (_: Exception) {
    null
} catch (_: LinkageError) {
    null
}

private fun unixFileChangeToken(file: File): ExpansionFileChange? {
    val path = file.toPath()
    if (Files.getFileStore(path).type() !in unixFilesystemsWithChangeTime) return null
    val attrs = Files.readAttributes(path, "unix:mode,dev,ino,ctime", NOFOLLOW_LINKS)
    val type = (attrs["mode"] as Int) and 0xf000
    if (type != 0x8000 && type != 0x4000) return null // regular file or directory
    val device = attrs["dev"] as Long
    val inode = attrs["ino"] as Long
    val change = (attrs["ctime"] as FileTime).toInstant()
    if (device == 0L || inode == 0L) return null
    return ExpansionFileChange("$device:$inode:${change.epochSecond}:${change.nano}", change.epochSecond)
}

private val unixFilesystemsWithChangeTime = setOf("apfs", "hfs", "ext2", "ext3", "ext4", "btrfs", "xfs", "zfs", "tmpfs")

private interface ExpansionKernel32 : StdCallLibrary {
    fun CreateFileW(name: WString, access: Int, share: Int, security: Pointer?, creation: Int,
                    flags: Int, template: Pointer?): Pointer?
    fun GetFileInformationByHandleEx(handle: Pointer, infoClass: Int, info: Pointer, size: Int): Boolean
    fun GetVolumeInformationByHandleW(handle: Pointer, volumeName: Pointer?, volumeNameSize: Int,
        serial: Pointer?, componentLength: Pointer?, flags: Pointer?, fsName: Pointer, fsNameSize: Int): Boolean
    fun CloseHandle(handle: Pointer): Boolean
}

private object WindowsFileChanges {
    private val kernel = Native.load("kernel32", ExpansionKernel32::class.java)
    private const val FILE_READ_ATTRIBUTES = 0x80
    private const val FILE_SHARE_READ = 1
    private const val OPEN_EXISTING = 3
    private const val FILE_FLAG_BACKUP_SEMANTICS = 0x02000000
    private const val FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000
    private const val FILE_ATTRIBUTE_REPARSE_POINT = 0x400
    private const val FILE_BASIC_INFO = 0
    private const val FILE_ID_INFO = 18
    private const val WINDOWS_EPOCH_SECONDS = 11_644_473_600L

    fun read(file: File): ExpansionFileChange? {
        // Share readers only so a still-open writer cannot hide pending timestamp updates.
        val handle = kernel.CreateFileW(WString(file.absolutePath), FILE_READ_ATTRIBUTES, FILE_SHARE_READ,
            null, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS or FILE_FLAG_OPEN_REPARSE_POINT, null) ?: return null
        val address = Pointer.nativeValue(handle)
        if (address == -1L || (Native.POINTER_SIZE == 4 && address.toInt() == -1)) return null
        try {
            return Memory(64).use { info ->
                // Removable FAT volumes may synthesize timestamps without an independent ChangeTime.
                if (!kernel.GetVolumeInformationByHandleW(handle, null, 0, null, null, null, info, 32)) return null
                val filesystem = info.getWideString(0)
                if (filesystem != "NTFS" && filesystem != "ReFS") return null
                // FILE_BASIC_INFO has four LARGE_INTEGER timestamps and a DWORD attributes field.
                if (!kernel.GetFileInformationByHandleEx(handle, FILE_BASIC_INFO, info, 40)) return null
                if (info.getInt(32) and FILE_ATTRIBUTE_REPARSE_POINT != 0) return null
                val changeTicks = info.getLong(24)

                // FILE_ID_INFO has a 64-bit volume serial followed by the complete 128-bit file ID.
                if (!kernel.GetFileInformationByHandleEx(handle, FILE_ID_INFO, info, 24)) return null
                val volume = info.getLong(0)
                val idLow = info.getLong(8)
                val idHigh = info.getLong(16)
                if (volume == 0L || (idLow == 0L && idHigh == 0L)) return null
                ExpansionFileChange("$volume:$idHigh:$idLow:$changeTicks", changeTicks / 10_000_000 - WINDOWS_EPOCH_SECONDS)
            }
        } finally {
            kernel.CloseHandle(handle)
        }
    }
}
