/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.build;

import javassist.*;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/** 固定上游版本，确定性打包；产物不进入应用运行时依赖。 */
public final class BrowserKernelPackaging {
    public static final String GECKO = "157.0.20260924084938";
    public static final String JCEF = "jcef-d3de827+cef-146.0.10+g8219561+chromium-146.0.7680.179";
    private static final long PART_SIZE = 95L * 1024 * 1024;
    public static String id(String engine, String version, String platform, String arch) {
        return engine + "-" + version + "-v1-" + platform + "-" + arch;
    }
    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
    public static String hash(byte[] data) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(data)); }
    public static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b & 255)); return out.toString();
    }
    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : new TreeMap<>(entries).entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey()); entry.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0)); z.putNextEntry(entry); z.write(e.getValue()); z.closeEntry();
            }
        }
        return out.toByteArray();
    }
    private static Map<String, byte[]> entries(byte[] bytes) throws IOException {
        Map<String, byte[]> result = new TreeMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e; while ((e = z.getNextEntry()) != null) if (!e.isDirectory()) result.put(e.getName(), z.readAllBytes());
        }
        return result;
    }
    private static void write(File file, byte[] bytes) throws IOException {
        file.getParentFile().mkdirs(); Files.write(file.toPath(), bytes);
    }
    public static void gecko(File aar, File androidJar, File output, String componentVersion, File references) throws Exception {
        clearPackages(output);
        validateComponentVersion(componentVersion);
        Map<String, byte[]> all = entries(Files.readAllBytes(aar.toPath()));
        Map<String, byte[]> classes = entries(all.get("classes.jar"));
        ClassPool pool = new ClassPool(true);
        pool.appendClassPath(androidJar.getAbsolutePath());
        pool.appendClassPath(new ByteArrayClassPath("org.mozilla.gecko.GeckoAppShell", classes.get("org/mozilla/gecko/GeckoAppShell.class")));
        // Javassist needs the complete upstream class path to preserve stack maps and JNI signatures.
        File inputClasses = new File(output, "gecko-input-classes.jar"); write(inputClasses, all.get("classes.jar"));
        pool.appendClassPath(inputClasses.getAbsolutePath());
        CtClass paths = pool.makeClass("org.mozilla.gecko.mozglue.RwjsKernelPaths");
        paths.addMethod(CtNewMethod.make("public static String abi() { if (android.os.Process.is64Bit()) return android.os.Build.SUPPORTED_64_BIT_ABIS[0]; return android.os.Build.SUPPORTED_32_BIT_ABIS[0]; }", paths));
        paths.addField(CtField.make("private static java.io.File selectedRoot;", paths));
        paths.addMethod(CtNewMethod.make("public static synchronized java.io.File root() { if (selectedRoot != null) return selectedRoot; java.io.File base = new java.io.File(org.mozilla.gecko.GeckoAppShell.getApplicationContext().getNoBackupFilesDir(), \"browser/kernels\"); String prefix = \"gecko-" + GECKO + "-v1-android-\" + abi(); String name = prefix + \"-k" + componentVersion + "\"; java.io.File pointer = new java.io.File(base, \".active-gecko-" + GECKO + "-v1-android-\" + abi()); if (pointer.isFile()) { try { if (pointer.length() > 256) throw new java.io.IOException(\"Invalid kernel pointer\"); byte[] bytes = java.nio.file.Files.readAllBytes(pointer.toPath()); name = new String(bytes, \"UTF-8\").trim(); } catch (java.io.IOException failure) { throw new java.lang.IllegalStateException(\"Cannot select browser kernel\", failure); } } if (!name.startsWith(prefix + \"-k\") || !name.matches(\"[A-Za-z0-9_.+-]+\")) throw new java.lang.IllegalStateException(\"Invalid kernel identity\"); selectedRoot = new java.io.File(base, name); return selectedRoot; }", paths));
        paths.addMethod(CtNewMethod.make("public static String library(String name) { if (!name.matches(\"[A-Za-z0-9_]+\")) throw new IllegalArgumentException(\"Invalid Gecko library\"); java.io.File f = new java.io.File(root(), \"lib/lib\" + name + \".so\"); if (!new java.io.File(root(), \".ready\").isFile() || !f.isFile()) throw new IllegalStateException(\"Browser kernel is not installed: \" + name); return f.getAbsolutePath(); }", paths));
        paths.addMethod(CtNewMethod.make("public static String resources() { java.io.File f = new java.io.File(root(), \"gecko-resources.zip\"); if (!f.isFile()) throw new IllegalStateException(\"Browser resources are missing\"); return f.getAbsolutePath(); }", paths));
        StringBuilder dependencyMethod = new StringBuilder("public static String[] dependencies(String name) { String arch = abi();");
        for (String abi : List.of("arm64-v8a", "armeabi-v7a", "x86_64")) {
            String prefix = "jni/" + abi + "/";
            dependencyMethod.append("if (arch.equals(").append(quote(abi)).append(")) {");
            for (Map.Entry<String, byte[]> e : all.entrySet()) {
                if (!e.getKey().startsWith(prefix) || !e.getKey().endsWith(".so")) continue;
                String library = e.getKey().substring(prefix.length());
                List<String> needed = elfDependencies(e.getValue()).stream().filter(n -> all.containsKey(prefix + n))
                    .map(n -> quote(n.substring(3, n.length() - 3))).toList();
                dependencyMethod.append("if (name.equals(").append(quote(library.substring(3, library.length() - 3))).append(")) return new String[]{")
                    .append(String.join(",", needed)).append("};");
            }
            dependencyMethod.append("}");
        }
        dependencyMethod.append("return new String[0]; }");
        paths.addMethod(CtNewMethod.make(dependencyMethod.toString(), paths));
        paths.addField(CtField.make("private static final java.util.Set loaded = new java.util.HashSet();", paths));
        paths.addMethod(CtNewMethod.make("public static synchronized void load(String name) { if (loaded.contains(name)) return; String[] needed = dependencies(name); for (int i = 0; i < needed.length; i++) load(needed[i]); java.lang.System.load(library(name)); loaded.add(name); }", paths));
        CtClass loader = pool.get("org.mozilla.gecko.mozglue.GeckoLoader");
        CtMethod lookup = loader.getDeclaredMethod("getLibraryPath", new CtClass[]{pool.get("java.lang.String")});
        if (!lookup.getSignature().equals("(Ljava/lang/String;)Ljava/lang/String;")) throw new IllegalStateException("Unsupported GeckoLoader ABI");
        lookup.setBody("{ return org.mozilla.gecko.mozglue.RwjsKernelPaths.library($1); }");
        CtMethod load = loader.getDeclaredMethod("doLoadLibrary", new CtClass[]{pool.get("android.content.Context"), pool.get("java.lang.String")});
        if (!load.getSignature().equals("(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/Throwable;")) throw new IllegalStateException("Unsupported Gecko load entry");
        // The upstream fallback calls loadLibrary (a name), not load (an absolute path).
        // CrashHelper enters here before GeckoAppShell has an application Context.
        load.setBody("{ if (org.mozilla.gecko.GeckoAppShell.getApplicationContext() == null) org.mozilla.gecko.GeckoAppShell.setApplicationContext($1.getApplicationContext()); try { org.mozilla.gecko.mozglue.RwjsKernelPaths.load($2); return null; } catch (java.lang.Throwable failure) { throw new java.lang.RuntimeException(\"Cannot load external Gecko library: \" + $2, failure); } }");
        // Android's namespace has no search path for downloaded DT_NEEDED libraries.
        // Preload exact upstream ELF dependencies before the original native bootstrap.
        CtMethod bootstrap = loader.getDeclaredMethod("loadGeckoLibs", new CtClass[]{pool.get("android.content.Context")});
        bootstrap.insertBefore("{ String[] needed = org.mozilla.gecko.mozglue.RwjsKernelPaths.dependencies(\"xul\"); for (int i = 0; i < needed.length; i++) org.mozilla.gecko.mozglue.RwjsKernelPaths.load(needed[i]); org.mozilla.gecko.mozglue.RwjsKernelPaths.load(\"mozavcodec\"); org.mozilla.gecko.mozglue.RwjsKernelPaths.load(\"softokn3\"); org.mozilla.gecko.mozglue.RwjsKernelPaths.load(\"freebl3\"); }");
        CtClass thread = pool.get("org.mozilla.gecko.GeckoThread");
        CtMethod args = thread.getDeclaredMethod("getMainProcessArgs");
        if (!args.getSignature().equals("()[Ljava/lang/String;")) throw new IllegalStateException("Unsupported GeckoThread ABI");
        final int[] calls = {0};
        args.instrument(new ExprEditor() { public void edit(MethodCall call) throws CannotCompileException {
            if (call.getMethodName().equals("getPackageResourcePath")) { calls[0]++; call.replace("{ $_ = org.mozilla.gecko.mozglue.RwjsKernelPaths.resources(); }"); }
        }});
        if (calls[0] != 1) throw new IllegalStateException("Gecko resource loader changed");
        classes.put(paths.getName().replace('.', '/') + ".class", paths.toBytecode());
        classes.put(loader.getName().replace('.', '/') + ".class", loader.toBytecode());
        classes.put(thread.getName().replace('.', '/') + ".class", thread.toBytecode());
        Map<String, byte[]> thin = new TreeMap<>(all);
        thin.keySet().removeIf(n -> n.startsWith("jni/") || n.equals("assets/omni.ja"));
        thin.put("classes.jar", zip(classes));
        write(new File(output, "gecko-thin.aar"), zip(thin));
        byte[] resources = zip(Map.of("assets/omni.ja", all.get("assets/omni.ja")));
        for (String abi : List.of("arm64-v8a", "armeabi-v7a", "x86_64")) {
            Map<String, byte[]> files = new TreeMap<>(); files.put("gecko-resources.zip", resources);
            String prefix = "jni/" + abi + "/";
            all.forEach((name, bytes) -> { if (name.startsWith(prefix)) files.put("lib/" + name.substring(prefix.length()), bytes); });
            pack(files, "gecko", GECKO, "android", abi, componentVersion, output, references);
        }
    }
    /** Read ELF DT_NEEDED at build time, so the Android loader never guesses an ABI's dependency order. */
    private static List<String> elfDependencies(byte[] data) throws IOException {
        if (data.length < 64 || data[0] != 0x7f || data[1] != 'E' || data[2] != 'L' || data[3] != 'F' || data[5] != 1) throw new IOException("Unsupported Gecko ELF");
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        boolean wide = data[4] == 2;
        long sectionStart = wide ? buffer.getLong(40) : Integer.toUnsignedLong(buffer.getInt(32));
        int entrySize = Short.toUnsignedInt(buffer.getShort(wide ? 58 : 46));
        int count = Short.toUnsignedInt(buffer.getShort(wide ? 60 : 48));
        for (int i = 0; i < count; i++) {
            int start = Math.toIntExact(sectionStart + (long)i * entrySize);
            if (buffer.getInt(start + 4) != 6) continue;
            long offset = wide ? buffer.getLong(start + 24) : Integer.toUnsignedLong(buffer.getInt(start + 16));
            long size = wide ? buffer.getLong(start + 32) : Integer.toUnsignedLong(buffer.getInt(start + 20));
            int stringsIndex = buffer.getInt(start + (wide ? 40 : 24));
            int stringHeader = Math.toIntExact(sectionStart + (long)stringsIndex * entrySize);
            long strings = wide ? buffer.getLong(stringHeader + 24) : Integer.toUnsignedLong(buffer.getInt(stringHeader + 16));
            List<String> result = new ArrayList<>();
            for (long p = offset; p < offset + size; p += wide ? 16 : 8) {
                long kind = wide ? buffer.getLong((int)p) : buffer.getInt((int)p);
                if (kind != 1) continue;
                long name = strings + (wide ? buffer.getLong((int)p + 8) : Integer.toUnsignedLong(buffer.getInt((int)p + 4)));
                int end = Math.toIntExact(name); while (data[end] != 0) end++;
                result.add(new String(data, (int)name, end - (int)name, java.nio.charset.StandardCharsets.UTF_8));
            }
            return result;
        }
        return List.of();
    }
    public static void desktop(File jar, String platform, String arch, File output, String componentVersion, File references) throws Exception {
        clearPackages(output);
        validateComponentVersion(componentVersion);
        Map<String, byte[]> files = new TreeMap<>();
        Set<String> executableFiles = new HashSet<>();
        try (ZipFile z = new ZipFile(jar)) {
            ZipEntry nativeEntry = z.stream().filter(e -> e.getName().endsWith(".tar.gz")).findFirst().orElseThrow();
            try (TarArchiveInputStream tar = new TarArchiveInputStream(new GzipCompressorInputStream(z.getInputStream(nativeEntry)))) {
                TarArchiveEntry e;
                while ((e = tar.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    if (!e.isFile()) throw new IOException("Unsupported native archive link: " + e.getName());
                    String name = e.getName().replaceFirst("^\\./", "");
                    if (name.startsWith("/") || Arrays.asList(name.split("/")).contains("..")) throw new IOException("Invalid native path");
                    files.put(name, tar.readAllBytes());
                    if ((e.getMode() & 0111) != 0) executableFiles.add(name);
                }
            }
        }
        pack(files, "jcef", JCEF, platform, arch, componentVersion, output, references, executableFiles);
    }
    private static void validateComponentVersion(String version) {
        if (!version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)") || version.equals("0.0.0")) throw new IllegalArgumentException("Invalid browser component version: " + version);
    }
    private static void clearPackages(File output) throws IOException {
        File[] old = output.listFiles();
        if (old != null) for (File file : old) if (file.isFile() && file.getName().startsWith("RWJS-Browser-") && (file.getName().endsWith(".json") || file.getName().endsWith(".kernel") || file.getName().matches(".*\\.kernel\\.\\d{3}"))) Files.delete(file.toPath());
    }
    private static void pack(Map<String, byte[]> files, String engine, String version, String platform, String arch, String componentVersion, File output, File references) throws Exception {
        pack(files, engine, version, platform, arch, componentVersion, output, references, Set.of());
    }
    private static void pack(Map<String, byte[]> files, String engine, String version, String platform, String arch, String componentVersion, File output, File references, Set<String> executableFiles) throws Exception {
        String kernelId = id(engine, version, platform, arch) + "-k" + componentVersion;
        String assetBase = "RWJS-Browser-" + engine + "-" + platform + "-" + arch + "-v" + componentVersion;
        String base = assetBase + ".kernel";
        File full = new File(output, base); full.getParentFile().mkdirs();
        // Stream the large package; do not keep an additional compressed engine in heap.
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(full)))) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey()); entry.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0)); zip.putNextEntry(entry); zip.write(e.getValue()); zip.closeEntry();
            }
        }
        List<String> parts = new ArrayList<>();
        try (InputStream in = new BufferedInputStream(new FileInputStream(full))) {
            int index = 0; long remaining = full.length(); byte[] buffer = new byte[65536];
            while (remaining > 0) {
                File part = new File(output, base + String.format(".%03d", ++index));
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
                    long size = Math.min(remaining, PART_SIZE), left = size;
                    while (left > 0) { int n = in.read(buffer, 0, (int)Math.min(left, buffer.length)); if (n < 0) throw new EOFException(); out.write(buffer, 0, n); left -= n; }
                    remaining -= size;
                }
                parts.add("{\"name\":" + quote(part.getName()) + ",\"size\":" + part.length() + ",\"sha256\":" + quote(hash(part)) + "}");
            }
        }
        List<String> inventory = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) inventory.add("{\"path\":" + quote(e.getKey()) + ",\"size\":" + e.getValue().length + ",\"sha256\":" + quote(hash(e.getValue())) + (executableFiles.contains(e.getKey()) ? ",\"executable\":true" : "") + "}");
        String manifest = "{\"format\":1,\"id\":" + quote(kernelId) + ",\"engine\":" + quote(engine) + ",\"version\":" + quote(version) + ",\"platform\":" + quote(platform) + ",\"arch\":" + quote(arch) + ",\"releaseTag\":\"latest\",\"sha256\":" + quote(hash(full)) + ",\"parts\":[" + String.join(",", parts) + "],\"files\":[" + String.join(",", inventory) + "],\"componentVersion\":" + quote(componentVersion) + "}";
        File metadata = new File(output, assetBase + ".json"); write(metadata, manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File reference = new File(references, "browser-kernels/" + platform + "-" + arch + ".json");
        // The bundled requirement defines compatibility; newer manifests are trusted from our latest Release.
        write(reference, ("{\"id\":" + quote(kernelId) + ",\"releaseTag\":\"latest\",\"manifest\":" + quote(metadata.getName()) + ",\"manifestSha256\":" + quote(hash(metadata)) + ",\"embeddedManifest\":" + manifest + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.delete(full.toPath());
    }
}
