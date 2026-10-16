/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.rwpp.AppContext
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.logger
import io.github.rwpp.ui.BackgroundConnectionPermissionPanel
import io.github.rwpp.ui.BackgroundConnectionPermissionState
import io.github.rwpp.ui.BackgroundConnectionSettingsRow
import io.github.rwpp.ui.UI
import org.koin.compose.koinInject

@Composable
actual fun BackgroundConnectionSettingsEntry() {
    val context = LocalContext.current
    val activity = remember(context) { context.findPermissionActivity() }
    val appContext = koinInject<AppContext>()
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) {
        context.getSharedPreferences("background_connection_permissions", Context.MODE_PRIVATE)
    }
    var notificationsEnabled by remember { mutableStateOf(false) }
    var notificationChannelEnabled by remember { mutableStateOf(false) }
    var canRequestNotifications by remember { mutableStateOf(false) }
    var unrestricted by remember { mutableStateOf(false) }
    var requestInFlight by rememberSaveable { mutableStateOf(false) }
    var visible by rememberSaveable { mutableStateOf(false) }

    fun refreshPermissions() {
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationChannelEnabled = manager.getNotificationChannel(KEEP_ALIVE_NOTIFICATION_CHANNEL_ID)
            ?.importance != NotificationManager.IMPORTANCE_NONE
        unrestricted = appContext.isBackgroundNetworkUnrestricted()
        // 首次申请与可再次询问走系统授权框；永久拒绝、旧系统或关闭渠道时打开通知设置。
        canRequestNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            activity != null && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            (!preferences.getBoolean("notifications_requested", false) ||
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestInFlight = false
        refreshPermissions()
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        requestInFlight = false
        // 电池优化设置不承诺返回 RESULT_OK；始终重新读取系统状态，包括用户拒绝/返回的情况。
        refreshPermissions()
    }

    fun appDetailsIntent() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"))

    fun launchSettings(vararg intents: Intent) {
        requestInFlight = true
        for (intent in intents) {
            val result = runCatching { settingsLauncher.launch(intent) }
            if (result.isSuccess) return
            logger.warn("[KEEPALIVE] cannot open ${intent.action}", result.exceptionOrNull())
        }
        requestInFlight = false
        refreshPermissions()
        UI.showWarning(readI18n("settings.backgroundConnectionSettingsFailed"))
    }

    fun openNotificationSettings() {
        val appNotifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (notificationsEnabled && !notificationChannelEnabled) {
            launchSettings(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, KEEP_ALIVE_NOTIFICATION_CHANNEL_ID),
                appNotifications,
                appDetailsIntent(),
            )
        } else {
            launchSettings(appNotifications, appDetailsIntent())
        }
    }

    val requestNotifications: () -> Unit = {
        if (canRequestNotifications) {
            preferences.edit().putBoolean("notifications_requested", true).apply()
            requestInFlight = true
            runCatching { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .onFailure {
                    logger.warn("[KEEPALIVE] cannot request notification permission", it)
                    openNotificationSettings()
                }
        } else {
            openNotificationSettings()
        }
    }
    val requestBattery: () -> Unit = {
        if (unrestricted) {
            launchSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetailsIntent())
        } else {
            launchSettings(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                appDetailsIntent(),
            )
        }
    }

    DisposableEffect(context, lifecycleOwner) {
        refreshPermissions()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state = BackgroundConnectionPermissionState(
        notificationsEnabled = notificationsEnabled,
        notificationChannelEnabled = notificationChannelEnabled,
        batteryUnrestricted = unrestricted,
    )
    BackgroundConnectionSettingsRow(state, onClick = { visible = true })
    if (visible) BackgroundConnectionPermissionPanel(
        state = state,
        busy = requestInFlight,
        onNotifications = requestNotifications,
        onBattery = requestBattery,
        onSystemSettings = { launchSettings(appDetailsIntent()) },
        onDismissRequest = { visible = false },
    )
}

private tailrec fun Context.findPermissionActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findPermissionActivity()
    else -> null
}
