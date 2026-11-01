/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

// Runtime dependencies from GeckoView 157.0.20260924084938's POM. Keep these when using the thin AAR.
val geckoDependencyConfiguration = if (project.name == "rwpp-core") "androidMainImplementation" else "implementation"
listOf(
    "androidx.annotation:annotation:1.10.0", "androidx.annotation:annotation-experimental:1.6.0",
    "androidx.collection:collection:1.6.0", "androidx.core:core:1.19.0",
    "androidx.lifecycle:lifecycle-common:2.11.0", "androidx.lifecycle:lifecycle-process:2.11.0",
    "com.google.android.gms:play-services-fido:21.3.1", "org.yaml:snakeyaml:2.2",
    "androidx.media3:media3-common:1.11.0", "androidx.media3:media3-datasource:1.11.0",
    "androidx.media3:media3-decoder:1.11.0", "androidx.media3:media3-exoplayer:1.11.0",
    "androidx.media3:media3-exoplayer-hls:1.11.0",
).forEach { dependencies.add(geckoDependencyConfiguration, it) }
