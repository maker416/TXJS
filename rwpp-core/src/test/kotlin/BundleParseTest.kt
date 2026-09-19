/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import net.peanuuutz.tomlkt.Toml
import net.peanuuutz.tomlkt.TomlTable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * i18n bundle 在运行时才被解析，TOML 非法（如点分键路径冲突）会导致 App 启动即闪退，
 * 且编译期无任何校验。本测试用与 [io.github.rwpp.impl.BaseGameI18nResolverImpl]
 * 相同的解析方式实跑两个 bundle，把这类错误挡在编译期。
 */
class BundleParseTest {

    private val bundleDir = File("src/commonMain/composeResources/files")
    private val bundleNames = listOf("bundle_zh.toml", "bundle_en.toml")

    @Test
    fun bundlesAreValidToml() {
        bundleNames.forEach { name ->
            val file = File(bundleDir, name)
            assertTrue(file.exists(), "bundle not found: ${file.absolutePath}")
            // 非法即抛异常，测试随之失败
            Toml.parseToTomlTable(file.readText())
        }
    }

    @Test
    fun modSyncKeysExistInAllBundles() {
        bundleNames.forEach { name ->
            val table = Toml.parseToTomlTable(File(bundleDir, name).readText())
            val modSync = table["modSync"] as? TomlTable
            assertNotNull(modSync, "[modSync] table missing in $name")
            listOf(
                "hostPreparing",
                "hostPreparingTimeout",
                "hostPreparingGone",
                "syncFailed",
                "syncFailedDetail",
                "statusUploading",
                "statusReady",
                "statusError",
                "pendingPeersTitle",
                "clearPeers",
                "phaseWaitingHost",
                "phaseDownloading",
                "phaseApplying",
                "phaseJoining",
                "phaseSynced",
                "cancellingTitle",
                "cancellingDetail",
                "reconnecting",
                "reconnectFailed",
            ).forEach { key ->
                assertTrue(modSync.containsKey(key), "modSync.$key missing in $name")
            }
            val room = table["multiplayer"] as? TomlTable
            val roomTable = room?.get("room") as? TomlTable
            assertNotNull(roomTable, "[multiplayer.room] missing in $name")
            listOf(
                "playersStillSyncing",
                "forceStartTitle",
                "forceStartMessage",
                "forceStartConfirm",
            ).forEach { key ->
                assertTrue(roomTable.containsKey(key), "multiplayer.room.$key missing in $name")
            }
        }
    }

    @Test
    fun menuAnnouncementKeysExistInAllBundles() {
        bundleNames.forEach { name ->
            val table = Toml.parseToTomlTable(File(bundleDir, name).readText())
            val menu = table["menu"] as? TomlTable
            assertNotNull(menu, "[menu] table missing in $name")
            listOf(
                "announcement",
                "announcementLatest",
                "announcementCurrent",
                "announcementUpToDate",
                "announcementUpdateAvailable",
                "announcementNoData",
            ).forEach { key ->
                assertTrue(menu.containsKey(key), "menu.$key missing in $name")
            }
        }
    }

    @Test
    fun replayKeysExistInAllBundles() {
        bundleNames.forEach { name ->
            val table = Toml.parseToTomlTable(File(bundleDir, name).readText())
            val replays = table["replays"] as? TomlTable
            assertNotNull(replays, "[replays] table missing in $name")
            listOf(
                "title",
                "filter",
                "empty",
                "emptyFiltered",
                "search",
                "recordingOff",
                "loadFailed",
                "count",
                "players",
                "play",
                "sort",
                "group",
                "mapFilter",
                "playerFilter",
                "versionFilter",
                "sortTimeNewest",
                "sortTimeOldest",
                "sortMapAsc",
                "sortMapDesc",
                "sortPlayersDesc",
                "sortPlayersAsc",
                "sortSizeDesc",
                "sortSizeAsc",
                "groupNone",
                "groupMap",
                "groupPlayers",
                "groupDate",
                "groupVersion",
                "dateAll",
                "dateToday",
                "dateWeek",
                "dateMonth",
                "mapAll",
                "playersAll",
                "versionAll",
                "playersUnknown",
                "dateUnknown",
                "versionUnknown",
                "resetFilters",
                "collapseGroup",
                "expandGroup",
                "import",
                "importPreparing",
                "importing",
                "importSuccess",
                "importInvalid",
                "importFailed",
                "importExistsTitle",
                "importExistsMessage",
                "importOverwrite",
            ).forEach { key ->
                assertTrue(replays.containsKey(key), "replays.$key missing in $name")
            }
        }
    }

    @Test
    fun protectedModHintKeysExistInAllBundles() {
        bundleNames.forEach { name ->
            val table = Toml.parseToTomlTable(File(bundleDir, name).readText())
            val mod = table["mod"] as? TomlTable
            assertNotNull(mod, "[mod] table missing in $name")
            listOf("protectedLoadHint", "protectedLoadHintMany", "starting").forEach { key ->
                assertTrue(mod.containsKey(key), "mod.$key missing in $name")
            }
        }
    }

    @Test
    fun accountKeysExistInAllBundles() {
        bundleNames.forEach { name ->
            val table = Toml.parseToTomlTable(File(bundleDir, name).readText())
            val account = table["account"] as? TomlTable
            assertNotNull(account, "[account] table missing in $name")
            listOf(
                "title",
                "loginTitle",
                "loginHint",
                "registerTitle",
                "registerHint",
                "identifier",
                "displayName",
                "password",
                "confirmPassword",
                "passwordMismatch",
                "identifierRequired",
                "passwordRequired",
                "switchToRegister",
                "switchToLogin",
                "notLoggedInTitle",
                "notLoggedInBody",
                "login",
                "register",
                "logout",
                "logoutConfirmTitle",
                "logoutConfirmBody",
                "refresh",
                "loggedIn",
                "loading",
                "profileFailed",
                "applyNameToMultiplayer",
                "applyNameConfirm",
                "multiplayerNameHint",
                "sessionExpired",
            ).forEach { key ->
                assertTrue(account.containsKey(key), "account.$key missing in $name")
            }
        }
    }

    @Test
    fun friendsKeysExistInAllBundles() {
        bundleNames.forEach { name ->
            val table = Toml.parseToTomlTable(File(bundleDir, name).readText())
            val friends = table["friends"] as? TomlTable
            assertNotNull(friends, "[friends] table missing in $name")
            listOf(
                "title",
                "add",
                "addTitle",
                "addHint",
                "alreadyAdded",
                "cannotAddSelf",
                "empty",
                "noMessages",
                "send",
                "inputHint",
                "chatTitle",
            ).forEach { key ->
                assertTrue(friends.containsKey(key), "friends.$key missing in $name")
            }
        }
    }
}
