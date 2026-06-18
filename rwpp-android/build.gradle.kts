import java.util.Properties

plugins {
    kotlin("android")
    kotlin("plugin.serialization")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

group = "io.github.rwpp"
version = rootProject.version

ksp {
    arg("outputDir", project.buildDir.absolutePath + "/generated")
    arg("lib", "android-game-lib")
    arg("libDir", "$rootDir/lib")
    arg("pathType", "Path")
}

dependencies {
    api(project(":rwpp-core"))
    implementation(fileTree(
        "dir" to "$rootDir/dx",
        "include" to listOf("*.jar")
    ))
    implementation("com.github.getActivity:XXPermissions:20.0")
    implementation("com.github.tony19:logback-android:3.0.0")
    compileOnly(fileTree(
        "dir" to rootDir.absolutePath + "/lib",
        "include" to "android-game-lib.jar",
    ))
    val koinVersion = findProperty("koin.version") as String
    implementation("io.insert-koin:koin-android:$koinVersion")
    runtimeOnly("party.iroiro.luajava:android:4.0.2:lua54@aar")

    implementation(fileTree(
        "dir" to rootDir.absolutePath,
        "include" to "javassist4android.jar",
    ))
    val koinAnnotationsVersion = findProperty("koin.annotations.version") as String
    ksp("io.insert-koin:koin-ksp-compiler:$koinAnnotationsVersion")
    ksp(project(":rwpp-ksp"))
}

val releaseKeystorePropertiesFile = rootProject.file("build/key/keystore.properties")
val releaseKeystoreProperties = Properties()
if (releaseKeystorePropertiesFile.exists()) {
    releaseKeystorePropertiesFile.inputStream().use {
        releaseKeystoreProperties.load(it)
    }
}

data class ReleaseSigning(
    val storeFile: java.io.File,
    val keyAlias: String,
    val keyPassword: String,
    val storePassword: String,
)

fun requireReleaseSigningConfigured(): ReleaseSigning {
    if (!releaseKeystorePropertiesFile.exists()) {
        error(
            """
            Release APK 需要签名配置。
            请运行: powershell -File build/generate-keystore.ps1
            或复制 build/key/keystore.properties.example 为 keystore.properties 并填入密钥信息。
            """.trimIndent()
        )
    }
    val storePath = releaseKeystoreProperties.getProperty("storeFile")
        ?: error("build/key/keystore.properties 缺少 storeFile")
    val storeFile = rootProject.file(storePath)
    if (!storeFile.exists()) {
        error("Release 密钥库不存在: ${storeFile.absolutePath}")
    }
    val keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
        ?: error("build/key/keystore.properties 缺少 keyAlias")
    val keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
        ?: error("build/key/keystore.properties 缺少 keyPassword")
    val storePassword = releaseKeystoreProperties.getProperty("storePassword")
        ?: error("build/key/keystore.properties 缺少 storePassword")
    return ReleaseSigning(storeFile, keyAlias, keyPassword, storePassword)
}

// 配置阶段仅在校验通过时注册签名，避免不完整 keystore.properties 拖垮 assembleDebug / test 等任务
val releaseSigning = runCatching { requireReleaseSigningConfigured() }.getOrNull()

android {
    compileSdk = (findProperty("android.compileSdk") as String).toInt()
    buildToolsVersion = "34.0.0"
    namespace = "io.github.rwpp"

    useLibrary("org.apache.http.legacy")

    packaging {
        resources.excludes.add("META-INF/*")
    }

    dexOptions {
        javaMaxHeapSize = "2G"
    }

    signingConfigs {
        releaseSigning?.let { signing ->
            create("release") {
                storeFile = signing.storeFile
                keyAlias = signing.keyAlias
                keyPassword = signing.keyPassword
                storePassword = signing.storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // isDebuggable = true
        }
    }

    sourceSets["main"].manifest.srcFile("src/main/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/main/res")
    sourceSets["main"].resources.srcDir(project.buildDir.absolutePath + "/generated")
    sourceSets["main"].resources.include("config.toml")
    sourceSets["main"].resources.srcDir(rootDir.absolutePath + "/lib")
    sourceSets["main"].resources.include("android-game-lib.jar")

    // For KSP
//    applicationVariants.configureEach {
//        val variant: com.android.build.gradle.api.ApplicationVariant = this
//        kotlin.sourceSets {
//            getByName(name) {
//                kotlin.srcDir("build/generated/ksp/${variant.name}/kotlin")
//            }
//        }
//    }

    lint {
        disable += "NullSafeMutableLiveData"
        abortOnError = false
        checkReleaseBuilds = false
    }

    defaultConfig {
        applicationId = "io.github.rwjs"
        minSdk = (findProperty("android.minSdk") as String).toInt()
        targetSdk = (findProperty("android.targetSdk") as String).toInt()
        versionCode = (System.currentTimeMillis() / 60000).toInt()
        versionName = rootProject.version.toString()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
        compilerOptions {
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }
}

tasks.matching {
    it.name.equals("assembleRelease", ignoreCase = true) ||
        it.name.equals("bundleRelease", ignoreCase = true)
}.configureEach {
    doFirst {
        requireReleaseSigningConfigured()
    }
}

