/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap.agent;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.ClassFileTransformer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Startup agent: sizes are supplied by the running JVM, never by a layout formula. */
public final class HeapAgent {
    private static volatile Instrumentation instrumentation;

    private HeapAgent() {}

    public static void premain(String options, Instrumentation value) {
        instrumentation = Objects.requireNonNull(value);
    }

    public static boolean isAvailable() {
        return instrumentation != null;
    }

    /** Worker-only diagnostic optimization; the caller installs it before loading the core. */
    public static void addTransformer(ClassFileTransformer transformer) {
        requireInstrumentation().addTransformer(Objects.requireNonNull(transformer));
    }

    private static Instrumentation requireInstrumentation() {
        Instrumentation current = instrumentation;
        if (current == null) {
            throw new IllegalStateException(
                "Java Agent 未初始化，请使用 -javaagent:<工具 JAR 路径> 启动测量进程"
            );
        }
        return current;
    }

    /**
     * Explicitly opens JDK object fields only to this measurement worker's module. Keeping
     * this separate from objectSize makes missing access a hard failure in normal traversal.
     * It also avoids a command line containing every internal package a real graph may reach.
     */
    public static void openHeapPackages() {
        Instrumentation current = requireInstrumentation();
        Module measurementModule = HeapAgent.class.getModule();
        Set<Module> targets = Collections.singleton(measurementModule);
        for (String name : new String[] { "java.base", "java.desktop" }) {
            Module module = ModuleLayer.boot().findModule(name).orElse(null);
            if (module == null) continue;
            if (!current.isModifiableModule(module)) {
                throw new IllegalStateException("当前 JVM 不允许打开堆测量模块: " + name);
            }
            Map<String, Set<Module>> openings = new HashMap<>();
            for (String packageName : module.getPackages()) {
                openings.put(packageName, targets);
            }
            current.redefineModule(
                module,
                Collections.emptySet(),
                Collections.emptyMap(),
                openings,
                Collections.emptySet(),
                Collections.emptyMap()
            );
        }
    }

    public static long objectSize(Object value) {
        return requireInstrumentation().getObjectSize(Objects.requireNonNull(value));
    }
}
