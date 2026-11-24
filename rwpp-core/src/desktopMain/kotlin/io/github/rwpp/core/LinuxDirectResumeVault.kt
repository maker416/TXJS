/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One binary Secret Service item. The collection is owned by the desktop login session. */
internal object LinuxDirectResumeVault : DirectResumeVault {
    private val secret by lazy { Native.load("secret-1", LibSecret::class.java) }
    private val gio by lazy { Native.load("gio-2.0", Gio::class.java) }
    private val schema by lazy { secret.secret_schema_new("io.github.rwpp.DirectResume", 0, "version", 0, null) }
    private val timer by lazy { Executors.newSingleThreadScheduledExecutor { Thread(it, "direct-keyring").apply { isDaemon = true } } }

    override fun read(): ByteArray? = access { cancel, error ->
        val value = secret.secret_password_lookup_binary_sync(schema, cancel, error, "version", "1", null)
            ?: return@access null
        try {
            val size = NativeLongByReference()
            val data = secret.secret_value_get(value, size)
            val count = size.value.toLong()
            if (count !in 0L..MAX_DIRECT_RESUME_BYTES.toLong()) throw DirectResumeCorruptException("Invalid reconnect item size")
            data.getByteArray(0, count.toInt())
        } finally {
            secret.secret_value_unref(value)
        }
    }

    override fun write(bytes: ByteArray) {
        require(bytes.size <= MAX_DIRECT_RESUME_BYTES)
        access { cancel, error ->
            val value = secret.secret_value_new(bytes, NativeLong(bytes.size.toLong()), "application/octet-stream")
            try {
                val stored = secret.secret_password_store_binary_sync(schema, "default", "RWJS Direct reconnect", value,
                    cancel, error, "version", "1", null)
                if (stored == 0) throw IOException("Unable to store reconnect item")
            } finally {
                secret.secret_value_unref(value)
            }
        }
    }

    override fun delete() {
        access { cancel, error -> secret.secret_password_clear_sync(schema, cancel, error, "version", "1", null) }
    }

    private fun <T> access(operation: (Pointer, PointerByReference) -> T): T {
        try {
            val cancel = gio.g_cancellable_new()
            // A locked keyring must not hold a join open indefinitely. The synchronized cleanup
            // keeps the timeout thread from using a released native reference.
            var finished = false
            val deadline = timer.schedule({
                synchronized(cancel) { if (!finished) gio.g_cancellable_cancel(cancel) }
            }, 3, TimeUnit.SECONDS)
            val error = PointerByReference()
            try {
                val result = operation(cancel, error)
                if (error.value != null) throw IOException("Desktop keyring is unavailable")
                return result
            } finally {
                deadline.cancel(false)
                synchronized(cancel) { finished = true }
                gio.g_object_unref(cancel)
                error.value?.let { gio.g_error_free(it) }
            }
        } catch (e: LinkageError) {
            throw IOException("Desktop keyring library is unavailable", e)
        }
    }

    private interface LibSecret : Library {
        fun secret_schema_new(name: String, flags: Int, vararg attributes: Any?): Pointer
        fun secret_value_new(data: ByteArray, size: NativeLong, contentType: String): Pointer
        fun secret_value_get(value: Pointer, size: NativeLongByReference): Pointer
        fun secret_value_unref(value: Pointer)
        fun secret_password_lookup_binary_sync(schema: Pointer, cancel: Pointer, error: PointerByReference,
                                              vararg attributes: Any?): Pointer?
        fun secret_password_store_binary_sync(schema: Pointer, collection: String, label: String, value: Pointer,
                                             cancel: Pointer, error: PointerByReference, vararg attributes: Any?): Int
        fun secret_password_clear_sync(schema: Pointer, cancel: Pointer, error: PointerByReference,
                                      vararg attributes: Any?): Int
    }

    private interface Gio : Library {
        fun g_cancellable_new(): Pointer
        fun g_cancellable_cancel(cancel: Pointer)
        fun g_object_unref(value: Pointer)
        fun g_error_free(error: Pointer)
    }
}
