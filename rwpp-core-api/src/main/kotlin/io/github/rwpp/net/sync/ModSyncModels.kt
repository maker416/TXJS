/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync

import io.github.rwpp.game.mod.NetworkModDescriptor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 同步服务器协议中的模组描述符。
 *
 * 与 Go 服务端 JSON 对齐（snake_case 字段名）：`{"name":..., "size":..., "sha256":...}`。
 * [size] 与 [sha256] 描述的是模组「传输字节」（文件夹模组为压缩后的 zip 字节），
 * 与 [NetworkModDescriptor.payloadSize] 语义一致。
 */
@Serializable
data class SyncModDescriptor(
    val name: String,
    val size: Long,
    @SerialName("sha256") val sha256: String,
)

/** 转换为本地网络模组缓存使用的描述符。 */
fun SyncModDescriptor.toNetwork(): NetworkModDescriptor =
    NetworkModDescriptor(name, size, sha256)

/** 转换为同步服务器协议使用的描述符（sha256 归一为小写）。 */
fun NetworkModDescriptor.toSync(): SyncModDescriptor =
    SyncModDescriptor(name, payloadSize, normalizedSha256)

/**
 * 房主注册房间同步记录请求体。对应 `POST /rooms`。
 *
 * 重复 key 时服务端校验 [secret] 匹配，否则返回 409。
 */
@Serializable
data class RoomRegisterRequest(
    val key: String,
    val secret: String,
    @SerialName("game_version") val gameVersion: String,
    val mods: List<SyncModDescriptor>,
)

/**
 * 房间同步清单响应。对应 `GET /rooms/{key}`。
 *
 * 404 表示无同步记录（[ModSyncClient.fetchManifest] 返回 null）。
 */
@Serializable
data class RoomManifestResponse(
    val status: String,
    @SerialName("game_version") val gameVersion: String = "",
    val mods: List<SyncModDescriptor>,
) {
    /** 房主已完成全部模组上传，可直接开始 diff/下载。 */
    val isReady: Boolean get() = status == SyncStatus.READY

    /** 房主仍在准备（上传中），加入方应轮询等待。 */
    val isPreparing: Boolean get() = status == SyncStatus.PREPARING
}

/** 追加房间别名请求体（发布后绑定 server_id）。对应 `POST /rooms/{key}/alias`。 */
@Serializable
data class AliasRequest(
    val alias: String,
)

/** 批量查询 blob 缺失情况请求体。对应 `POST /files/check`。 */
@Serializable
data class FilesCheckRequest(
    val hashes: List<String>,
)

/** 批量查询 blob 缺失情况响应体：[missing] 为服务端尚不存在的 sha256 列表。 */
@Serializable
data class FilesCheckResponse(
    val missing: List<String>,
)

/** 服务端统一错误体：`{"error": "..."}`。 */
@Serializable
data class ErrorResponse(
    val error: String = "",
)

/** 房间同步状态枚举值（与 Go 服务端字符串对齐）。 */
object SyncStatus {
    const val PREPARING = "preparing"
    const val READY = "ready"
}

/** 加入者带外进度 phase（与 Go 服务端字符串对齐）。 */
object SyncPeerPhase {
    const val WAITING_HOST = "waiting_host"
    const val DOWNLOADING = "downloading"
    const val APPLYING = "applying"
    /** 模组已应用完毕，正在建立游戏连接、尚未出现在房间列表。 */
    const val JOINING = "joining"
}

/**
 * 加入者 upsert 进度请求体。对应 `PUT /rooms/{key}/peers/{peer_id}`。
 * 服务端会补写 peer_id / updated_at；客户端上报不必带这两项。
 */
@Serializable
data class SyncPeerUpsertRequest(
    @SerialName("display_name") val displayName: String,
    val phase: String,
    @SerialName("current_mod_name") val currentModName: String = "",
    @SerialName("current_bytes") val currentBytes: Long = 0L,
    @SerialName("current_total") val currentTotal: Long = 0L,
    @SerialName("mod_index") val modIndex: Int = 0,
    @SerialName("mod_count") val modCount: Int = 0,
)

/**
 * 服务端返回的加入者进度快照。对应 list/upsert 响应中的单个 peer。
 */
@Serializable
data class SyncPeerProgress(
    @SerialName("peer_id") val peerId: String = "",
    @SerialName("display_name") val displayName: String = "",
    val phase: String = "",
    @SerialName("current_mod_name") val currentModName: String = "",
    @SerialName("current_bytes") val currentBytes: Long = 0L,
    @SerialName("current_total") val currentTotal: Long = 0L,
    @SerialName("mod_index") val modIndex: Int = 0,
    @SerialName("mod_count") val modCount: Int = 0,
    @SerialName("updated_at") val updatedAt: String = "",
)

/** `GET /rooms/{key}/peers` 响应体。 */
@Serializable
data class SyncPeerListResponse(
    val peers: List<SyncPeerProgress> = emptyList(),
)

/**
 * 房主 UI 使用的加入者同步进度快照（对齐旧 HostTransferSnapshot 口径）。
 * [currentBytes]/[currentTotal] 均为**当前模组**进度。
 */
data class SyncPeerSnapshot(
    val peerId: String,
    val displayName: String,
    val phase: String,
    val currentModName: String,
    val currentBytes: Long,
    val currentTotal: Long,
    val modIndex: Int,
    val modCount: Int,
)

fun SyncPeerProgress.toSnapshot(): SyncPeerSnapshot = SyncPeerSnapshot(
    peerId = peerId,
    displayName = displayName,
    phase = phase,
    currentModName = currentModName,
    currentBytes = currentBytes,
    currentTotal = currentTotal,
    modIndex = modIndex,
    modCount = modCount,
)
