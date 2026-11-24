/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.rwpp.appKoin
import java.io.File
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal actual fun platformDirectResumeVault(): DirectResumeVault = AndroidDirectResumeVault(
    File(appKoin.get<Context>().applicationContext.noBackupFilesDir, "direct-resume-v1.bin"),
    "io.github.rwpp.direct-resume.v1",
)

internal class AndroidDirectResumeVault(file: File, private val alias: String) : EncryptedDirectResumeFile(file) {
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw DirectResumeCorruptException("Missing reconnect encryption key")
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(spec)
            generateKey()
        }
    }

    override fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        check(cipher.iv.size == IV_BYTES)
        return cipher.iv + cipher.doFinal(bytes)
    }

    override fun decrypt(bytes: ByteArray): ByteArray {
        if (bytes.size < IV_BYTES + TAG_BITS / 8) throw DirectResumeCorruptException("Incomplete reconnect file")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return try {
            cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        } catch (e: AEADBadTagException) {
            throw DirectResumeCorruptException("Reconnect file authentication failed", e)
        }
    }

    private companion object {
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
