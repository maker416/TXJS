/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.LanguageHelper
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.logger
import io.github.rwpp.themeDir
import io.github.rwpp.widget.defaultRWPPColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.peanuuutz.tomlkt.Toml
import java.io.File

/**
 * 主题美术包（`.rwtheme`）控制器：扫描、导入、启用/停用、删除与热切换。
 *
 * v1 覆盖范围：主题配色、启动器背景图、主菜单标题图、文本覆盖（含主菜单按钮文字）。
 * 所有资源缺失或损坏时都回落内置外观；校验全部在导入时完成，运行时路径零解析失败风险。
 */
object ArtThemeController {

    /** 一个已解压安装的主题包（`themes/<id>/`）。 */
    class LoadedTheme(
        val dir: File,
        val spec: ArtThemeSpec,
        val backgroundFile: File?,
        val titleFile: File?,
        val iconFile: File?,
        val stringsZhFile: File?,
        val stringsEnFile: File?,
    ) {
        val id: String get() = spec.theme.id

        /** 以内置 RWPP 主题为底、按包内 `[colors]` 逐角色覆盖生成的 [ColorScheme]。 */
        val colorScheme: ColorScheme by lazy {
            val parsed = spec.colors.mapNotNull { (role, hex) ->
                if (role in SUPPORTED_COLOR_ROLES) {
                    parseColorHex(hex)?.let { role to Color(it) }
                } else null
            }.toMap()
            fun pick(role: String, fallback: Color) = parsed[role] ?: fallback
            defaultRWPPColorScheme.copy(
                primary = pick("primary", defaultRWPPColorScheme.primary),
                onPrimary = pick("onPrimary", defaultRWPPColorScheme.onPrimary),
                primaryContainer = pick("primaryContainer", defaultRWPPColorScheme.primaryContainer),
                onPrimaryContainer = pick("onPrimaryContainer", defaultRWPPColorScheme.onPrimaryContainer),
                secondary = pick("secondary", defaultRWPPColorScheme.secondary),
                onSecondary = pick("onSecondary", defaultRWPPColorScheme.onSecondary),
                secondaryContainer = pick("secondaryContainer", defaultRWPPColorScheme.secondaryContainer),
                onSecondaryContainer = pick("onSecondaryContainer", defaultRWPPColorScheme.onSecondaryContainer),
                tertiary = pick("tertiary", defaultRWPPColorScheme.tertiary),
                onTertiary = pick("onTertiary", defaultRWPPColorScheme.onTertiary),
                tertiaryContainer = pick("tertiaryContainer", defaultRWPPColorScheme.tertiaryContainer),
                onTertiaryContainer = pick("onTertiaryContainer", defaultRWPPColorScheme.onTertiaryContainer),
                background = pick("background", defaultRWPPColorScheme.background),
                onBackground = pick("onBackground", defaultRWPPColorScheme.onBackground),
                surface = pick("surface", defaultRWPPColorScheme.surface),
                onSurface = pick("onSurface", defaultRWPPColorScheme.onSurface),
                surfaceContainer = pick("surfaceContainer", defaultRWPPColorScheme.surfaceContainer),
                error = pick("error", defaultRWPPColorScheme.error),
                onError = pick("onError", defaultRWPPColorScheme.onError),
                inversePrimary = pick("inversePrimary", defaultRWPPColorScheme.inversePrimary),
            )
        }
    }

    /** 当前启用的主题包；null 表示使用内置外观。 */
    var activeTheme by mutableStateOf<LoadedTheme?>(null)
        private set

    /** 已安装（扫描到的合法）主题包列表。 */
    var installedThemes by mutableStateOf<List<LoadedTheme>>(emptyList())
        private set

    private var initialized = false

    /** 测试钩子：覆盖主题根目录（默认 [themeDir]）。 */
    internal var themeRootOverride: File? = null

    private val themesRoot: File get() = themeRootOverride ?: File(themeDir)

    private val settings: Settings get() = appKoin.get<Settings>()

    /** 进程内一次：扫描并按 [Settings.selectedThemePack] 恢复激活项。 */
    fun ensureInitialized() {
        if (initialized) return
        initialized = true
        scan()
        val saved = settings.selectedThemePack
        if (!saved.isNullOrBlank()) {
            if (installedThemes.any { it.id == saved }) {
                apply(saved)
            } else {
                logger.warn("Theme pack $saved not found, fallback to built-in theme")
                settings.selectedThemePack = null
            }
        }
    }

    /** 重新扫描主题根目录的一级子目录。坏包跳过并记日志，不中断整体列表。 */
    fun scan() {
        val root = themesRoot
        installedThemes = root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val tomlFile = File(dir, "theme.toml")
                if (!tomlFile.exists()) return@mapNotNull null
                val (spec, validation) = runCatching { validateThemeToml(tomlFile.readText()) }
                    .getOrElse {
                        logger.warn("Failed to read theme.toml in ${dir.name}: ${it.message}")
                        return@mapNotNull null
                    }
                if (spec == null) {
                    logger.warn("Invalid theme pack ${dir.name}: ${validation.errors.joinToString()}")
                    return@mapNotNull null
                }
                LoadedTheme(
                    dir = dir,
                    spec = spec,
                    backgroundFile = pickFile(dir, "background.png", "background.jpg"),
                    titleFile = pickFile(dir, "title.png"),
                    iconFile = pickFile(dir, "icon.png"),
                    stringsZhFile = pickFile(dir, "strings_zh.toml"),
                    stringsEnFile = pickFile(dir, "strings_en.toml"),
                )
            }
            ?.sortedBy { it.spec.theme.name }
            ?: emptyList()
    }

    /** 启用指定主题包；传 null 停用并回落内置外观。配色/标题/背景经 Compose 状态热生效。 */
    fun apply(id: String?) {
        val target = if (id == null) null else installedThemes.firstOrNull { it.id == id }
        activeTheme = target
        settings.selectedThemePack = target?.id
        applyStringOverrides()
    }

    /**
     * 按当前语言把激活主题包的 strings_*.toml 应用为 i18n 覆盖表。
     * 语言切换导致 bundle 重载后需要重放（见 BaseGameI18nResolverImpl.init）。
     */
    fun applyStringOverrides() {
        val theme = activeTheme
        val file = when (LanguageHelper.resolveEffectiveLanguage(settings)) {
            "zh" -> theme?.stringsZhFile
            else -> theme?.stringsEnFile
        }
        if (theme == null || file == null || !file.exists()) {
            setI18nOverride(null)
            return
        }
        val table = runCatching {
            Toml.parseToTomlTable(file.readText().replace("\r", "\n"))
        }.onFailure {
            logger.warn("Failed to parse theme strings ${file.name}: ${it.message}")
        }.getOrNull()
        setI18nOverride(table)
    }

    /** 导入 `.rwtheme` zip：校验与解压由 [ThemePackInstaller] 完成，成功后重扫列表。 */
    suspend fun import(file: File): ThemeInstallResult = withContext(Dispatchers.IO) {
        ThemePackInstaller.install(file, themesRoot).also { result ->
            if (result.success) scan()
        }
    }

    /** 删除主题包；正在启用的先停用。拒绝删除主题根目录之外或不存在的路径。 */
    fun delete(id: String): Boolean {
        val root = themesRoot.canonicalFile
        val dir = File(themesRoot, id)
        if (!dir.exists()) return false
        if (!dir.canonicalFile.path.startsWith(root.path)) return false
        if (activeTheme?.id == id) apply(null)
        val ok = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        scan()
        return ok
    }

    private fun pickFile(dir: File, vararg names: String): File? =
        names.firstNotNullOfOrNull { name -> File(dir, name).takeIf { it.exists() } }
}
