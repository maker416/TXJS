/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * 启动器页面级导航状态（单一事实来源）。
 *
 * 历史上页面切换靠 [UI] 中 11 个相互独立的 showXxxView 布尔量驱动，
 * 组合上允许任意多个页面同时为 true（此时多个 AnimatedVisibility 叠画），
 * 互斥全靠开发者自觉保证。现在收敛为单选状态：任何时刻至多一个页面级页面可见，
 * 主菜单即「无页面打开」的落点。
 *
 * 账号页/好友页不属于本枚举：它们被设计为可叠加在房间页之上的上层页
 *（房间内邀请好友就地登录、房间名片发私信），仍由 [UI.showAccountView] /
 * [UI.showFriendsView] 两个独立布尔量驱动。
 *
 * 旧的 11 个 showXxxView 布尔量保留为 [pageBinding] 的读写代理，既有调用点零改动；
 * 新代码请直接使用 [navigateTo] / [closePage] / [launcherPage]。
 */
sealed interface LauncherPage {
    /** 主菜单（无页面打开时的落点）。 */
    data object MainMenu : LauncherPage
    /** 单人游戏子菜单（战役/生存/遭遇战/沙盒入口）。 */
    data object SinglePlayer : LauncherPage
    /** 任务（战役）页。 */
    data object Mission : LauncherPage
    /** 生存模式页。 */
    data object Survival : LauncherPage
    /** 多人游戏列表页。 */
    data object Multiplayer : LauncherPage
    /** 等待房间页（单人遭遇战/沙盒与多人共用）。 */
    data object Room : LauncherPage
    /** 回放页。 */
    data object Replay : LauncherPage
    /** 设置页。 */
    data object Settings : LauncherPage
    /** 模组与地图页。 */
    data object Mods : LauncherPage
    /** 扩展页。 */
    data object Extensions : LauncherPage
    /** 资源浏览器页。 */
    data object ResourceBrowser : LauncherPage
    /** 开源信息页。 */
    data object OpenSourceInfo : LauncherPage
}

/**
 * 当前页面级页面（返回栈栈顶）。
 * 写入请走 [navigateTo] / [navigateBack] / [resetNavigation]，或经 [pageBinding] 代理的旧布尔量。
 */
var launcherPage: LauncherPage by mutableStateOf(LauncherPage.MainMenu)
    private set

/**
 * 页面返回栈（不含当前页）。只在主线程读写；
 * 不驱动渲染（渲染只看 [launcherPage]），故无需 Compose 状态。
 */
private val pageStack = ArrayDeque<LauncherPage>()

/** 前进一步：当前页压栈，进入 [page]（与当前页相同则忽略，防止重复压栈）。 */
fun navigateTo(page: LauncherPage) {
    if (launcherPage == page) return
    pageStack.addLast(launcherPage)
    launcherPage = page
}

/** 返回上一级：弹出栈顶；栈空（或栈顶即主菜单）时回主菜单。 */
fun navigateBack() {
    launcherPage =
        if (pageStack.isEmpty()) LauncherPage.MainMenu
        else pageStack.removeLast()
}

/**
 * 硬跳转：清空返回栈并直达 [page]。
 * 用于被踢回列表、接受房间邀请等「放弃当前导航上下文」的系统驱动转场。
 */
fun resetNavigation(page: LauncherPage) {
    pageStack.clear()
    launcherPage = page
}

/** 仅当当前页面是 [page] 时返回上一级；否则视为过期调用，忽略。 */
fun closePage(page: LauncherPage) {
    if (launcherPage == page) navigateBack()
}

/**
 * 旧 showXxxView 布尔量的读写代理：读 = 当前页（栈顶）是否为 [page]；
 * 写 true = [navigateTo]（压栈前进）；写 false = [closePage]（返回上一级）。
 * 让既有调用点在语义不变的前提下零改动迁移到单一导航状态：
 * 「关当前页再开目标页」的成对写法最终都收敛为正确的前进/返回。
 */
fun pageBinding(page: LauncherPage): ReadWriteProperty<Any?, Boolean> =
    object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = launcherPage == page

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            if (value) navigateTo(page) else closePage(page)
        }
    }
