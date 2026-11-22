/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import groovy.json.JsonSlurper
import java.net.URI

group = "io.github.rwpp"

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.github.gmazzo.buildconfig")
    id("com.google.devtools.ksp")
    id("maven-publish")
}

group = "io.github.rwpp"

val explicitApiProfile = providers.gradleProperty("clientApiProfile").orNull
val taskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val androidDebugTasks = setOf("assembleDebug", "bundleDebug", "packageDebug", "installDebug")
val releaseTasks = setOf("assembleRelease", "bundleRelease", "packageRelease", "installRelease",
    "packageReleaseUberJarForCurrentOS", "packageInnoDistribution", "packageDistributionForCurrentOS")
val buildsDebug = taskNames.any { it in androidDebugTasks }
val buildsRelease = taskNames.any { it in releaseTasks }
require(explicitApiProfile != null || !buildsDebug || !buildsRelease) {
    "Debug and release share one API profile per Gradle invocation. Build separately or set -PclientApiProfile=official|test."
}
val apiProfile = explicitApiProfile ?: if (buildsDebug) "test" else "official"
require(apiProfile in setOf("official", "test")) { "clientApiProfile must be official or test." }
val apiProfilePath = "build/client-api-$apiProfile.json"
val apiProfileFile = rootProject.layout.projectDirectory.file(apiProfilePath)
val rawApiProfile = providers.fileContents(apiProfileFile).asText.orNull
    ?: error("Missing client API profile: $apiProfilePath")
val apiDefaults = runCatching { JsonSlurper().parseText(rawApiProfile) as? Map<*, *> }.getOrNull()
    ?: error("Invalid JSON object in $apiProfilePath")

fun profileString(key: String): String =
    (apiDefaults[key] as? String)?.trim() ?: error("$apiProfilePath: expected a string for $key")

fun checkedApiUrl(value: String, key: String, secure: Boolean = false, queryAllowed: Boolean = false): String {
    val uri = runCatching { URI(value) }.getOrNull()
    val url = runCatching { uri?.toURL() }.getOrNull()
    // URL understands Unicode hostnames while URI.host can be null for an IDN.
    val host = url?.host.orEmpty().lowercase()
    val scheme = url?.protocol
    val loopback = host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
    require(uri != null && url != null && host.isNotBlank() && '@' !in uri.rawAuthority.orEmpty()
        && (url.port == -1 || url.port in 1..65535)
        && (scheme == "https" || scheme == "http" && (!secure || loopback))
        && (queryAllowed || uri.rawQuery == null) && uri.rawFragment == null) {
        "$apiProfilePath: invalid URL for $key (check scheme, host, port, credentials, query and fragment)."
    }
    return value
}

fun profileUrl(key: String, secure: Boolean = false, queryAllowed: Boolean = false): String =
    checkedApiUrl(profileString(key), key, secure, queryAllowed)

fun profileMirrors(key: String): String {
    val items = apiDefaults[key] as? List<*>
    require(!items.isNullOrEmpty()) { "$apiProfilePath: expected a nonempty URL array for $key" }
    return items.map {
        val value = (it as? String)?.trim()
        require(value != null && ';' !in value) { "$apiProfilePath: expected one URL per item in $key" }
        checkedApiUrl(value, key).trimEnd('/')
    }.distinct().joinToString(";")
}

buildConfig {
    buildConfigField("VERSION", rootProject.version.toString())
    // 安装包渠道标识：打包时通过 -PonlineChannel=xxx 传入，默认 official。
    // 仅允许字母、数字、下划线、连字符（服务端同样校验）。
    val onlineChannel = (findProperty("onlineChannel") as String?)
        ?.trim()
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
        ?: "official"
    buildConfigField("ONLINE_CHANNEL", onlineChannel)
    buildConfigField("CLIENT_API_PROFILE", apiProfile)
    buildConfigField("ACCOUNT_API_URL", profileUrl("accountApiUrl").trimEnd('/'))
    val profileAccountAppKey = profileString("accountAppKey")
    require(profileAccountAppKey.matches(Regex("[A-Za-z0-9_-]{0,128}"))) {
        "$apiProfilePath: invalid client accountAppKey"
    }
    buildConfigField("ACCOUNT_APP_KEY", profileAccountAppKey)
    buildConfigField("FORUM_URL", profileUrl("forumUrl"))
    buildConfigField("OPEN_PLATFORM_URL", checkedApiUrl(
        providers.gradleProperty("openPlatformUrl").orNull?.trim() ?: profileString("openPlatformUrl"),
        "openPlatformUrl", secure = true).trimEnd('/'))
    buildConfigField("ROOM_LIST_API_URLS", profileMirrors("roomListApiUrls"))
    buildConfigField("MOD_SYNC_API_URLS", profileMirrors("modSyncApiUrls"))
    val playtime = profileString("modPlaytimeApiUrl")
    buildConfigField("MOD_PLAYTIME_API_URL",
        if (playtime.isEmpty()) "" else checkedApiUrl(playtime, "modPlaytimeApiUrl").trimEnd('/'))
    buildConfigField("ONLINE_PRESENCE_API_URL", profileUrl("onlinePresenceApiUrl").trimEnd('/'))
    buildConfigField("FEEDBACK_API_URL", checkedApiUrl(
        providers.gradleProperty("feedbackApiUrl").orNull?.trim() ?: profileString("feedbackApiUrl"),
        "feedbackApiUrl").trimEnd('/'))
    buildConfigField("UPDATE_API_URL", profileUrl("updateApiUrl", secure = true))
    val browserKernelUrl = profileUrl("browserKernelApiUrl", secure = true)
    val browserKernelUri = URI(browserKernelUrl)
    val useDefaultKernelPath = browserKernelUri.scheme.equals("https", ignoreCase = true) &&
        browserKernelUri.rawPath in setOf("", "/", "/latest.json")
    buildConfigField("BROWSER_KERNEL_API_URL", if (useDefaultKernelPath) {
        "https://${browserKernelUri.rawAuthority}/update/latest.json"
    } else browserKernelUrl)
    buildConfigField("ESPORTS_SITE_URL", profileUrl("esportsSiteUrl"))
    buildConfigField("RTS_BOX_SITE_URL", profileUrl("rtsBoxSiteUrl").trimEnd('/'))
    buildConfigField("RTS_BOX_SEARCH_API_URL", profileUrl("rtsBoxSearchApiUrl", secure = true))
    buildConfigField("RTS_BOX_LOGIN_API_URL", profileUrl("rtsBoxLoginApiUrl", secure = true))
    buildConfigField("RTS_BOX_HOT_LIST_URL", profileUrl("rtsBoxHotListUrl", secure = true, queryAllowed = true))
}

dependencies {
    val koinVersion = findProperty("koin.version") as String
    val koinAnnotationsVersion = findProperty("koin.annotations.version") as String
    api("io.insert-koin:koin-core:$koinVersion")
    api("io.insert-koin:koin-annotations:${koinAnnotationsVersion}")
    api("org.slf4j:slf4j-api:2.0.16")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${findProperty("kotlin.coroutines.version")}")
    api("org.jetbrains.kotlin:kotlin-reflect:${findProperty("kotlin.version")}")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.eclipsesource.minimal-json:minimal-json:0.9.5")
    implementation("net.peanuuutz.tomlkt:tomlkt:0.3.7")
    compileOnly("org.javassist:javassist:3.30.2-GA")
    ksp("io.insert-koin:koin-ksp-compiler:$koinAnnotationsVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${findProperty("kotlin.version")}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${findProperty("kotlin.coroutines.version")}")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // InjectRedirectApplyTest 用真实 game-lib.jar 走 InjectApi 的 redirect/injectMethod 代码路径
    testImplementation("org.javassist:javassist:3.30.2-GA")
}

tasks.kotlinSourcesJar {
    archiveClassifier.set("sources")
}

tasks.test { exclude("**/ModLoadSafety*ProcessTest.class") }

val resourceLoadSafetyTest by tasks.registering(Test::class) {
    description = "Verify irreversible resource failures in isolated JVMs"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/ModLoadSafety*ProcessTest.class")
    maxHeapSize = "96m"
    forkEvery = 1
}
tasks.check { dependsOn(resourceLoadSafetyTest) }

tasks.register<Test>("lowMemoryModTransferTest") {
    description = "Verify mod transfer/cache operations under a 96 MiB Java heap"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    maxHeapSize = "96m"
    filter { includeTestsMatching("ModTransferMemoryTest") }
}

if (findProject(":rwpp-mod-heap-tool") != null) {
    tasks.register("estimateModHeap") {
        group = "rwpp"
        description = "兼容旧命令：转交真实桌面核心内存测量，用法：-Pmod=<路径> [-PgameRoot=<游戏目录>]"
        dependsOn(":rwpp-mod-heap-tool:measureModHeap")
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                group = "io.github.rwpp"
                artifactId = "api"
                version = rootProject.version.toString()

                from(components.getByName("kotlin"))
                artifact(tasks.kotlinSourcesJar)
            }
        }
    }
}
