/*
 * Copyright 2023-2025 RWPP contributors
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.net.ConnectionFailureReason
import io.github.rwpp.net.sync.direct.DirectJoinEndpoint
import io.github.rwpp.net.sync.direct.DirectModManifest
import io.github.rwpp.net.sync.direct.DirectModSyncTransport
import io.github.rwpp.net.sync.direct.DirectResumeGrant
import io.github.rwpp.net.sync.direct.sha256Hex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Stable storage owner and an in-process account lifecycle guard. */
internal class DirectResumeAccount(val owner: String, val isCurrent: () -> Boolean) {
    fun ensureCurrent() {
        if (!isCurrent()) throw IOException("Account changed during Direct recovery")
    }

    companion object {
        const val GUEST = "guest"
        fun guest(generation: String): String =
            resumeFingerprint(GUEST, generation)

        fun registered(issuer: String, userId: Long, generation: String): String =
            resumeFingerprint(issuer, userId.toString(), generation)
    }
}

internal class DirectResumeScope(
    val account: DirectResumeAccount,
    platform: String?,
    app: String?,
    server: String?,
) {
    private val portal = resumeFingerprint(platform.orEmpty(), app.orEmpty(), server.orEmpty())

    fun key(endpoint: DirectJoinEndpoint): String =
        resumeFingerprint(portal, endpoint.host.lowercase(), endpoint.port.toString())
}

internal sealed interface DirectResumeLookup {
    class Ready(val connection: DirectResumeConnection) : DirectResumeLookup
    class Unavailable(val reason: ConnectionFailureReason) : DirectResumeLookup
}

/** Keeps warm recovery available if the platform's protected storage is unavailable. */
internal class DirectResumeSessionStore(private val vaultFactory: (() -> DirectResumeVault)? = null) {
    private class Session(
        val instance: String,
        val room: RoomScope,
        val content: String,
        val secret: ByteArray,
        val issuedInLobby: Boolean,
        var observedMatch: Boolean = false,
    )

    private data class RoomScope(val exact: String, val room: String, val generation: Long) {
        fun isNextMatch(lobby: RoomScope): Boolean =
            room.isNotEmpty() && room == lobby.room && generation > lobby.generation &&
                generation - lobby.generation == 1L
    }

    private var owner = ""
    private val sessions = LinkedHashMap<String, Session>()
    private val removedWhileLocked = mutableSetOf<String>()
    private var loaded = false
    private var dirty = false
    private val vault by lazy { vaultFactory?.invoke() }

    @Synchronized
    fun store(scope: DirectResumeScope, endpoint: DirectJoinEndpoint, manifest: DirectModManifest, grant: DirectResumeGrant) {
        try {
            require(grant.serverInstance == manifest.serverInstance && grant.secret.size == SECRET_SIZE)
            if (!scope.account.isCurrent()) return
            load()
            if (!scope.account.isCurrent()) return
            if (owner != scope.account.owner) {
                eraseSessions()
                removedWhileLocked.clear()
            }
            owner = scope.account.owner
            val key = scope.key(endpoint)
            sessions.remove(key)?.secret?.fill(0)
            removedWhileLocked.remove(key)
            sessions[key] = Session(
                manifest.serverInstance, roomScope(manifest.scope), contentFingerprint(manifest), grant.secret.copyOf(),
                issuedInLobby = manifest.lobby && manifest.phase == "lobby",
                observedMatch = manifest.phase == "match",
            )
            trimSessions()
            changed()
        } finally {
            grant.secret.fill(0)
        }
    }

    @Synchronized
    fun open(
        scope: DirectResumeScope,
        endpoint: DirectJoinEndpoint,
        manifest: DirectModManifest,
        transport: DirectModSyncTransport,
    ): DirectResumeLookup {
        if (manifest.protocolVersion != 4 || manifest.lobby || manifest.phase != "match" ||
            manifest.capabilities and 3 != 3 || manifest.serverInstance.isBlank()) {
            return DirectResumeLookup.Unavailable(ConnectionFailureReason.RESUME_UNSUPPORTED)
        }
        val key = scope.key(endpoint)
        val session = find(scope, key) ?: return DirectResumeLookup.Unavailable(ConnectionFailureReason.RESUME_MISSING)
        val room = roomScope(manifest.scope)
        val validRoom = room.exact == session.room.exact || session.issuedInLobby && room.isNextMatch(session.room)
        if (session.instance != manifest.serverInstance || !validRoom || session.content != contentFingerprint(manifest)) {
            remove(key)
            return DirectResumeLookup.Unavailable(ConnectionFailureReason.RESUME_EXPIRED)
        }
        if (!session.observedMatch) {
            session.observedMatch = true
            changed()
        }
        return DirectResumeLookup.Ready(DirectResumeConnection(endpoint, manifest, transport, session.secret.copyOf(), scope.account))
    }

    /** A return to a later lobby in the same room confirms that an observed match ended. */
    @Synchronized
    fun observeLobby(scope: DirectResumeScope, endpoint: DirectJoinEndpoint, manifest: DirectModManifest): Boolean {
        if (manifest.protocolVersion != 4 || !manifest.lobby || manifest.phase != "lobby" || manifest.serverInstance.isBlank()) return false
        val key = scope.key(endpoint)
        val session = find(scope, key) ?: return false
        val room = roomScope(manifest.scope)
        val sameServer = session.instance == manifest.serverInstance
        val ended = sameServer && session.observedMatch && room.room.isNotEmpty() &&
            room.room == session.room.room && room.generation > session.room.generation
        if (ended || !sameServer || room.exact != session.room.exact || session.content != contentFingerprint(manifest)) {
            remove(key)
        }
        return ended
    }

    @Synchronized
    fun retainOwner(currentOwner: String, isCurrent: () -> Boolean) {
        if (!isCurrent()) return
        load(retryWrite = false)
        if (!isCurrent()) return
        if (owner.isNotEmpty() && owner != currentOwner) {
            eraseSessions()
            removedWhileLocked.clear()
            owner = ""
            changed()
        } else {
            save()
        }
    }

    @Synchronized
    fun clear() {
        loaded = true
        eraseSessions()
        removedWhileLocked.clear()
        owner = ""
        changed()
    }

    private fun find(scope: DirectResumeScope, key: String): Session? {
        if (!scope.account.isCurrent()) return null
        load()
        return sessions[key].takeIf { owner == scope.account.owner && scope.account.isCurrent() }
    }

    private fun remove(key: String) {
        sessions.remove(key)?.secret?.fill(0)
        if (!loaded) removedWhileLocked += key
        changed()
    }

    private fun eraseSessions() {
        sessions.values.forEach { it.secret.fill(0) }
        sessions.clear()
    }

    private fun load(retryWrite: Boolean = true) {
        if (loaded) {
            if (retryWrite) save()
            return
        }
        val bytes = try {
            vault?.read()
        } catch (_: DirectResumeCorruptException) {
            dirty = true
            null
        } catch (_: Exception) {
            // Keep using the warm records. Retry before touching unknown records on disk.
            return
        }
        loaded = true
        if (bytes == null) {
            removedWhileLocked.clear()
            if (retryWrite) save()
            return
        }
        val restored = LinkedHashMap<String, Session>()
        try {
            require(bytes.size <= MAX_DIRECT_RESUME_BYTES)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == STORE_VERSION)
                val restoredOwner = input.readUTF()
                val count = input.readUnsignedByte()
                require(count <= MAX_SESSIONS)
                repeat(count) {
                    val key = input.readUTF()
                    require(key !in restored)
                    val instance = input.readUTF()
                    val room = RoomScope(input.readUTF(), input.readUTF(), input.readLong())
                    val content = input.readUTF()
                    val issuedInLobby = input.readBoolean()
                    val observedMatch = input.readBoolean()
                    val secret = ByteArray(SECRET_SIZE)
                    restored[key] = Session(instance, room, content, secret, issuedInLobby, observedMatch)
                    input.readFully(secret)
                }
                require(input.read() == -1)
                if (owner.isEmpty()) owner = restoredOwner
                if (owner == restoredOwner) {
                    removedWhileLocked.forEach { key -> restored.remove(key)?.secret?.fill(0) }
                    sessions.forEach { (key, value) -> restored.remove(key)?.secret?.fill(0); restored[key] = value }
                    sessions.clear()
                    sessions.putAll(restored)
                    restored.clear()
                    trimSessions()
                }
            }
        } catch (_: Exception) {
            dirty = true
        } finally {
            restored.values.forEach { it.secret.fill(0) }
            removedWhileLocked.clear()
            bytes.fill(0)
        }
        if (retryWrite) save()
    }

    private fun trimSessions() {
        while (sessions.size > MAX_SESSIONS) {
            val oldest = sessions.keys.first()
            sessions.remove(oldest)?.secret?.fill(0)
            if (!loaded) removedWhileLocked += oldest
        }
    }

    private fun changed() {
        dirty = true
        save()
    }

    private fun save() {
        if (!loaded || !dirty) return
        try {
            val target = vault ?: return
            if (sessions.isEmpty()) {
                target.delete()
                dirty = false
                return
            }
            val buffer = SensitiveBuffer()
            try {
                DataOutputStream(buffer).use { output ->
                    output.writeInt(STORE_VERSION)
                    output.writeUTF(owner)
                    output.writeByte(sessions.size)
                    sessions.forEach { (key, session) ->
                        output.writeUTF(key)
                        output.writeUTF(session.instance)
                        output.writeUTF(session.room.exact)
                        output.writeUTF(session.room.room)
                        output.writeLong(session.room.generation)
                        output.writeUTF(session.content)
                        output.writeBoolean(session.issuedInLobby)
                        output.writeBoolean(session.observedMatch)
                        output.write(session.secret)
                    }
                }
                val bytes = buffer.toByteArray()
                try {
                    target.write(bytes)
                    dirty = false
                } finally {
                    bytes.fill(0)
                }
            } finally {
                buffer.wipe()
            }
        } catch (_: Exception) {
            // The in-memory changes stay usable and are retried on the next operation.
        }
    }

    private class SensitiveBuffer : ByteArrayOutputStream(32 * 1024) {
        fun wipe() { buf.fill(0) }
    }

    companion object {
        private const val STORE_VERSION = 1
        private const val MAX_SESSIONS = 32
        private const val SECRET_SIZE = 32

        val platform = DirectResumeSessionStore(::platformDirectResumeVault)

        private fun roomScope(scope: String): RoomScope {
            val exact = resumeFingerprint(scope)
            val separator = scope.lastIndexOf(':')
            val generation = scope.substring(separator + 1).toLongOrNull()
            if (separator <= 0 || generation == null || generation < 0) return RoomScope(exact, "", -1)
            return RoomScope(exact, resumeFingerprint(scope.substring(0, separator)), generation)
        }

        private fun contentFingerprint(manifest: DirectModManifest): String = fingerprint(buildList {
            add(manifest.gameVersion)
            add(manifest.hostUnitsChecksum.toString())
            add(manifest.contentRevision)
            add(manifest.artifact?.mediaType.orEmpty())
            manifest.artifact?.let { artifact ->
                add(artifact.name)
                add(artifact.version)
                add(artifact.size.toString())
                add(artifact.sha256)
            }
            manifest.mods.forEach { mod ->
                add(mod.name)
                add(mod.payloadSize.toString())
                add(mod.normalizedSha256)
            }
        })
    }
}

private fun resumeFingerprint(vararg fields: String): String = fingerprint(fields.asIterable())

private fun fingerprint(fields: Iterable<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val length = ByteBuffer.allocate(Int.SIZE_BYTES)
    for (field in fields) {
        val bytes = field.toByteArray(Charsets.UTF_8)
        digest.update(length.putInt(0, bytes.size).array())
        digest.update(bytes)
    }
    return sha256Hex(digest)
}
