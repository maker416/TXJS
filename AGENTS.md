# AGENTS.md

## Cursor Cloud specific instructions

### Project overview

This is **RWPP (铁锈战争极速版)** — a Kotlin Multiplatform game launcher for "Rusted Warfare". It uses Jetpack Compose Multiplatform for UI (Desktop + Android), Gradle 8.12, Kotlin 2.1.20, and targets JDK 21.

### Environment requirements

- **JDK 21** — set `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`
- **Android SDK** — installed at `/opt/android-sdk`, set `ANDROID_HOME=/opt/android-sdk` and `ANDROID_SDK_ROOT=/opt/android-sdk`. Requires platform `android-35` and build-tools `34.0.0`.
- **Display** — `DISPLAY=:1` is available for GUI tests (X11 server running on the VM).

### Build commands

| Task | Command |
|------|---------|
| Compile all modules | `./gradlew compileKotlin --no-daemon` |
| Run tests | `./gradlew test --no-daemon` |
| Build desktop UberJar | `./gradlew rwpp-desktop:packageReleaseUberJarForCurrentOS --no-daemon` |
| Android lint (may have internal errors) | `./gradlew rwpp-android:lint --no-daemon` |

### Known caveats

1. **`./gradlew rwpp-desktop:run` does not work in dev** — the `jvmArgs` in `compose.desktop.application` contain `$ROOTDIR` variables intended for native distributions. Use the UberJar approach instead to test the desktop app.
2. **Running the UberJar** — the game launcher requires Slick/lwjgl libraries at runtime. Use: `java -cp "rwpp-desktop/build/compose/jars/RWPP-linux-x64-<version>-release.jar:lib/slick.jar:lib/lwjgl.jar:lib/lwjgl_util.jar:lib/game-lib.jar:lib/jinput.jar:lib/ibxm.jar:lib/jogg-0.0.7.jar:lib/jorbis-0.0.15.jar:lib/tinylinepp.jar:lib/jnlp.jar" io.github.rwpp.desktop.MainKt`
3. **Game files are required for full functionality** — the launcher needs the actual Rusted Warfare game files (especially `game-lib.jar` content) to perform bytecode injection. Without game files it will show the Inject Console and report `gameLibNotExists: true`.
4. **SKIKO GL fallback** — in headless/Xvfb environments, Skiko will fallback from GL to software rendering, logging `[SKIKO] warn: Fallback to next API`. This is expected.
5. **Android lint internal crash** — the `rwpp-android:lint` task may fail with `IncompatibleClassChangeError` in `NonNullableMutableLiveDataDetector`. The build config already has `abortOnError = false` but the task still exits non-zero. This is a known AGP/lint compatibility issue.
6. **Test file location** — the single test file at `rwpp-core/src/test/kotlin/MainTest.kt` is a network integration test hitting the live `rtsbox.cn` API. It uses the deprecated "Android style" source dir and triggers a Gradle deprecation warning.
