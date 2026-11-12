#!/usr/bin/env python3
# Copyright 2023-2025 RWPP contributors
# 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
# Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
# https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
"""Linux 构建入口；只依赖 Python 标准库，Windows 安装器仍由 build.ps1 维护。"""

import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shlex
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from datetime import datetime

ROOT = Path(__file__).resolve().parent.parent
PART_SIZE = 95 * 1024 * 1024
TASKS = {
    "desktop": [":rwpp-desktop:packageReleaseUberJarForCurrentOS"],
    "linux": [":rwpp-desktop:packageReleaseUberJarForCurrentOS"],
    "test": [":rwpp-core:desktopTest", ":rwpp-core-api:test"],
    "android-debug": [":rwpp-android:assembleDebug"],
    "android-release": [":rwpp-android:assembleRelease"],
    "release-all": [":rwpp-desktop:packageReleaseUberJarForCurrentOS", ":rwpp-android:assembleRelease"],
    "browser-kernels": ["browserKernels"],
    "clean": ["clean"],
}
ALIASES = {
    "1": "desktop", "jar": "desktop", "fatjar": "desktop",
    "2": "linux", "installer": "linux", "linux-package": "linux",
    "3": "test", "tests": "test", "4": "android-debug", "debug": "android-debug",
    "5": "android-release", "release": "android-release", "6": "clean",
    "7": "browser-kernels", "kernels": "browser-kernels",
    "8": "release-all", "all-release": "release-all",
}


def bump_project_version(root):
    """只更新根客户端补丁版本；原子替换并保留 UTF-8 BOM 和换行。"""
    path = root / "build.gradle.kts"
    content = path.read_bytes().decode("utf-8")
    assignments = list(re.finditer(r'^(?:\ufeff)?[ \t]*version[ \t]*=[ \t]*"([^"\r\n]*)"', content, re.M))
    if len(assignments) != 1:
        raise ValueError('build.gradle.kts 必须包含唯一的 version = "x.y.z"。')
    match = assignments[0]
    previous = match[1]
    parts = re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", previous)
    if not parts:
        raise ValueError(f"客户端版本号必须为 x.y.z: {previous}")
    version = f"{parts[1]}.{parts[2]}.{int(parts[3]) + 1}"
    updated = content[:match.start(1)] + version + content[match.end(1):]
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + ".", suffix=".tmp", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(updated.encode("utf-8"))
        temporary.chmod(stat.S_IMODE(path.stat().st_mode))
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    print(f"[INFO] 客户端版本: {previous} -> {version}（已写入 build.gradle.kts）", flush=True)
    return version


def read_setting(path):
    if path.is_file():
        return next((line.strip().strip('"') for line in path.read_text(encoding="utf-8-sig").splitlines()
                     if line.strip() and not line.lstrip().startswith("#")), None)
    return None


def resolve_archive(root, explicit=None):
    adjacent = root / "build/Rusted-Warfare-linux.zip"
    value = explicit or (str(adjacent) if adjacent.is_file() else None) or os.environ.get("RW_GAME_ARCHIVE") or read_setting(root / "packaging/game-archive.local.txt")
    if not value:
        raise ValueError("请把 Rusted-Warfare-linux.zip 放在 build/build.sh 同级目录，或用 --game-archive 指定原版 ZIP。")
    path = Path(value).expanduser()
    return (path if path.is_absolute() else root / path).resolve()


def payload_file(name):
    parts = PurePosixPath(name).parts
    base = parts[-1]
    if parts[0] == "libs" and base.lower() in {"android.jar", "android-game-lib.jar"}:
        return False
    if re.search(r"^(io\.github\.rwpp\.|RWPP|RWJS|launcher\.)|\.(toml|bak|log|hprof)$", base, re.I) or base.lower() == "preferences.ini":
        return False
    if len(parts) == 1:
        return base in {"game-lib.jar", "steam_appid.txt"} or bool(re.search(r"\.(dll|dylib|so(?:\.\d+)*)$", base, re.I))
    return parts[0] in {"assets", "font", "libs", "res"}


def archive_plan(archive, linux=False):
    """先核对整个目录，再提取白名单；拒绝路径逃逸、链接和歧义根目录。"""
    entries = {}
    for entry in archive.infolist():
        name = entry.filename.replace("\\", "/")
        if name.startswith("/") or any(p in {".", ".."} or ":" in p for p in name.split("/")):
            raise ValueError(f"ZIP 含不安全路径: {name}")
        if stat.S_ISLNK(entry.external_attr >> 16):
            raise ValueError(f"ZIP 不允许符号链接: {name}")
        if entry.is_dir() or name.endswith("/"):
            continue
        if name in entries:
            raise ValueError(f"ZIP 含重复文件: {name}")
        entries[name] = entry
    roots = [name[:-len("game-lib.jar")] for name in entries
             if name == "game-lib.jar" or name.endswith("/game-lib.jar")]
    roots = [prefix for prefix in roots if any(n.startswith(prefix + "assets/") for n in entries)]
    if len(roots) != 1:
        raise ValueError("ZIP 必须含唯一游戏根目录（game-lib.jar 和非空 assets/），可带 Rusted Warfare/ 外层目录。")
    prefix = roots[0]
    result = {name[len(prefix):]: entry for name, entry in entries.items()
              if name.startswith(prefix) and payload_file(name[len(prefix):])}
    if len({n.casefold() for n in result}) != len(result):
        raise ValueError("ZIP 含大小写冲突文件，不能跨平台打包。")
    if linux:
        required = {"liblwjgl64.so", "libopenal64.so", "libjinput-linux64.so", "libs/lwjgl.jar", "libs/slick.jar"}
        missing = required - result.keys()
        if missing:
            raise ValueError("原版 ZIP 缺少 Linux x64 运行库: " + ", ".join(sorted(missing)))
    return result


def extract_payload(source, destination, linux=False):
    with zipfile.ZipFile(source) as archive:
        plan = archive_plan(archive, linux=linux)
        # 调用方提供新建的专用临时目录，不删除原版 ZIP 或用户目录。
        for name, entry in plan.items():
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(entry) as src, target.open("xb") as dest:
                shutil.copyfileobj(src, dest)
    print(f"[INFO] 原版资源: {len(plan)} 文件，{sum(e.file_size for e in plan.values()) / 1048576:.1f} MiB", flush=True)


def split_update(source, destination, base_name, part_size=PART_SIZE):
    if not 0 < part_size <= PART_SIZE:
        raise ValueError("分卷大小须为 1..95 MiB")
    zip_path = destination / (base_name + ".zip")
    try:
        with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_STORED) as archive:
            archive.write(source, source.name)
        digest = hashlib.sha256()
        index = 0
        with zip_path.open("rb") as src:
            while True:
                first = src.read(min(1024 * 1024, part_size))
                if not first:
                    break
                index += 1
                with Path(f"{zip_path}.{index:03d}").open("wb") as dest:
                    dest.write(first)
                    digest.update(first)
                    remaining = part_size - len(first)
                    while remaining:
                        block = src.read(min(1024 * 1024, remaining))
                        if not block:
                            break
                        dest.write(block)
                        digest.update(block)
                        remaining -= len(block)
        zip_path.with_suffix(".zip.sha256").write_text(f"{digest.hexdigest()}  {zip_path.name}\n", encoding="ascii")
    finally:
        zip_path.unlink(missing_ok=True)


def run(command, root, env=None):
    print("[RUN] " + shlex.join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), cwd=root, env=env, check=True)


def jdk_candidates():
    for key in ("JAVA_HOME", "JAVA_HOME_21_X64", "JDK_HOME"):
        if os.environ.get(key):
            yield Path(os.environ[key].strip().strip('"')).expanduser()
    java = shutil.which("java")
    if java:
        yield Path(java).resolve().parent.parent
    user_home = Path.home()
    for folder in (Path("/usr/lib/jvm"), Path("/usr/java"), Path("/opt/java"),
                   user_home / ".sdkman/candidates/java", user_home / ".jdks", user_home / ".local/share/jdks"):
        if folder.is_dir():
            try:
                yield from sorted(folder.iterdir(), reverse=True)
            except OSError:
                continue
    yield from Path("/opt").glob("jdk-21*")


def find_jdk(candidates=None):
    seen = set()
    for candidate in jdk_candidates() if candidates is None else candidates:
        try:
            java = (Path(candidate) / "bin/java").resolve()
            home = java.parent.parent
            if home in seen:
                continue
            seen.add(home)
            required = (java, home / "bin/javac", home / "bin/jlink", home / "jmods/java.base.jmod")
            if not all(path.is_file() for path in required):
                continue
            result = subprocess.run([str(java), "-version"], capture_output=True, text=True, timeout=15)
            match = re.search(r'version "(\d+)', result.stdout + result.stderr)
            if result.returncode == 0 and match and int(match[1]) == 21:
                return home
        except (OSError, ValueError, subprocess.SubprocessError):
            continue
    raise ValueError("未找到完整 JDK 21（需要 javac、jlink、jmods）。正常构建会尝试自动安装；--check / --no-install 不安装软件。")


def configure_java_environment(jdk):
    os.environ["JAVA_HOME"] = str(jdk)
    java_bin = str(jdk / "bin")
    entries = [entry for entry in os.get_exec_path() if entry != java_bin]
    os.environ["PATH"] = os.pathsep.join([java_bin] + entries)
    print(f"[ENV] JAVA_HOME = {jdk}", flush=True)


def read_properties(path):
    if not path.is_file():
        return {}
    result = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            # 本仓库的本机配置只用键值形式；兼容 Windows 转义的路径与空格。
            result[key.strip()] = re.sub(r"\\(.)", r"\1", value.strip())
    return result


def preflight(root, target, args):
    problems = []
    jdk = None
    jdk_error = None
    try:
        jdk = find_jdk()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        jdk_error = str(error)
    source = None
    if target in {"linux", "release-all"}:
        if platform.system() != "Linux" or platform.machine().lower() not in {"x86_64", "amd64"}:
            problems.append("当前原版资源包仅支持 Linux x64，请在 Linux x64 主机上打包。")
        try:
            source = resolve_archive(root, args.game_archive)
            with zipfile.ZipFile(source) as archive:
                plan = archive_plan(archive, linux=True)
            print(f"[INFO] 原版 ZIP: {source}（{len(plan)} 个运行资源文件）", flush=True)
        except (ValueError, OSError, zipfile.BadZipFile) as error:
            problems.append(str(error))
    # Desktop 编译不会执行 Android 任务；只有 APK 和含 Gecko 的内核包需要 SDK。
    if target in {"android-debug", "android-release", "browser-kernels", "release-all"}:
        sdk_value = read_properties(root / "local.properties").get("sdk.dir")
        sdk_value = sdk_value or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk_value or not (Path(sdk_value) / "platforms").is_dir() or not (Path(sdk_value) / "build-tools").is_dir():
            problems.append("Android SDK 无效；请在 local.properties 设置 Linux 的 sdk.dir（需 platforms/ 和 build-tools/）。")
    if target in {"android-release", "release-all"}:
        signing = read_properties(root / "build/key/keystore.properties")
        if not signing.get("storeFile") or not (root / signing["storeFile"]).is_file():
            problems.append("Release 签名不存在或 storeFile 无效；请保留原 build/key/ 密钥及配置，不要生成新密钥。")
    if target == "clean":
        content = (root / "build.gradle.kts").read_text(encoding="utf-8-sig")
        if not all(value in content for value in ("delete.clear()", "key/**", "artifacts/**", "*.ps1", "*.sh", "*.zip", "*.ZIP")):
            problems.append("根 clean 任务缺少脚本、密钥或产物保护规则。")
    if jdk is None:
        allow_install = not getattr(args, "check", False) and not getattr(args, "no_install", False) and target != "clean"
        if allow_install and not problems:
            run(["bash", root / "packaging/linux-environment.sh", "jdk"], root)
            try:
                jdk = find_jdk()
            except ValueError as error:
                raise ValueError("安装后仍未找到完整 JDK 21，请检查包管理器的软件包版本或设置 JAVA_HOME。") from error
        else:
            problems.insert(0, jdk_error)
    if problems:
        raise ValueError("\n".join(problems))
    return jdk, source


def desktop_jar(root, version):
    # 只接受本次任务的 fat jar 输出，不回退至旧 build/libs 的普通 jar。
    jars = list((root / "rwpp-desktop/build/compose/jars").glob(f"*-linux-x64-{version}-release.jar"))
    if len(jars) != 1:
        raise ValueError(f"应生成唯一 Linux x64 fat jar，实际 {len(jars)} 个；请清理 rwpp-desktop/build/compose/jars 后重试。")
    return jars[0]


def collect(root, target, args, jdk, source):
    version_match = re.search(r'^version\s*=\s*"([^"]+)"', (root / "build.gradle.kts").read_text(encoding="utf-8-sig"), re.M)
    if not version_match or not re.fullmatch(r"[A-Za-z0-9._+-]+", version_match[1]):
        raise ValueError("无法读取有效项目版本")
    version = version_match[1]
    subdir = {"desktop": "desktop-jar", "linux": "linux", "test": "test-reports"}.get(target, target)
    if target not in {"test", "browser-kernels"}:
        subdir += "-" + args.channel
    artifacts = root / "build/artifacts"
    artifacts.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".collect-", dir=artifacts) as temp:
        stage = Path(temp) / "output"
        stage.mkdir()
        if target == "desktop":
            shutil.copy2(desktop_jar(root, version), stage)
        elif target == "linux":
            bundle = stage / "RWJS"
            bundle.mkdir()
            extract_payload(source, bundle, linux=True)
            (bundle / "app").mkdir()
            shutil.copy2(desktop_jar(root, version), bundle / "app/RWJS.jar")
            shutil.copy2(root / "LICENSE", bundle / "LICENSE")
            shutil.copy2(root / "rwpp-desktop/logo.png", bundle / "logo.png")
            shutil.copy2(root / "packaging/linux-launcher.sh", bundle / "RWJS.sh")
            (bundle / "RWJS.sh").chmod(0o755)
            # java.se 加上全部 JDK 模块，避免反射/动态游戏插件缺模块；不带原版旧 JVM。
            run([jdk / "bin/jlink", "--add-modules", "ALL-MODULE-PATH", "--strip-debug",
                 "--no-header-files", "--no-man-pages", "--output", bundle / "runtime"], root)
            for directory in ("mods/units", "mods/maps", "logs", "extension", "generated_lib"):
                (bundle / directory).mkdir(parents=True, exist_ok=True)
            (stage / "使用说明.txt").write_text("解压到可写目录，在 RWJS 目录运行 ./RWJS.sh。已包含 Java 21，无需系统 Java。\n"
                "需 Linux x64 图形桌面和 OpenGL/OpenAL 系统依赖；首次注入后会自动重启。\n"
                "Linux 包暂供手动更新；不要覆盖或删除用户 mods、saves、replays 与配置。\n", encoding="utf-8")
            package = stage / f"RWJS-Linux-x64-{version}.tar.gz"
            with tarfile.open(package, "w:gz") as archive:
                archive.add(bundle, arcname="RWJS")
            digest = file_hash(package)
            (stage / (package.name + ".sha256")).write_text(f"{digest}  {package.name}\n", encoding="ascii")
        elif target.startswith("android-"):
            variant = target.removeprefix("android-")
            apks = list((root / f"rwpp-android/build/outputs/apk/{variant}").glob("*.apk"))
            if len(apks) != 1 or "unsigned" in apks[0].name:
                raise ValueError("必须生成唯一已签名 APK，拒绝收集缺失、unsigned 或不明确的旧产物。")
            shutil.copy2(apks[0], stage / f"RWJS-Android-{version}-{variant}.apk")
            split_update(apks[0], stage, "RWJS-Android")
            (stage / "发布说明.txt").write_text("上传全部 RWJS-Android.zip.001/.002/... 和 RWJS-Android.zip.sha256；完整 APK 供本地安装。\n"
                "分卷保留原 APK 签名；正式发布仅使用 android-release 且沿用原签名。\n", encoding="utf-8")
        elif target == "browser-kernels":
            files = list((root / "build/browser-kernels").glob("*/RWJS-Browser-*"))
            if not files:
                raise ValueError("浏览器内核附件未生成")
            for path in files:
                shutil.copy2(path, stage)
        elif target == "test":
            copied = False
            for module in ("rwpp-core", "rwpp-core-api"):
                for folder in ("reports/tests", "test-results"):
                    path = root / module / "build" / folder
                    if path.is_dir():
                        shutil.copytree(path, stage / module / folder)
                        copied = True
            if not copied:
                raise ValueError("测试报告未生成")
        (stage / "build-info.txt").write_text(f"Version: {version}\nChannel: {args.channel}\nTarget: {target}\n"
            f"BuiltAt: {datetime.now().astimezone().isoformat()}\nPlatform: {platform.platform()}\n", encoding="utf-8")
        dest = artifacts / subdir
        if dest.is_symlink():
            raise ValueError(f"产物目录不能是符号链接: {dest}")
        if dest.exists():
            shutil.rmtree(dest)
        stage.rename(dest)
    print(f"[DONE] 产物: {dest}", flush=True)


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as src:
        for block in iter(lambda: src.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main(argv=None):
    try:
        bump_project_version(ROOT)
    except (ValueError, OSError) as error:
        print(f"[ERROR] {error}", file=sys.stderr)
        return 1
    parser = argparse.ArgumentParser(description="RWJS Linux 打包工具（Python 3.9+、JDK 21）；每次启动脚本版本号立即 +0.0.1")
    parser.add_argument("target", nargs="?", help="linux / desktop / release-all / android-debug / android-release / test / browser-kernels / clean")
    parser.add_argument("--channel", "-Channel", default="official", help="渠道，默认 official")
    parser.add_argument("--game-archive", "-GameArchive", help="原版 ZIP，默认读取 build/Rusted-Warfare-linux.zip")
    parser.add_argument("--check", "-Check", action="store_true", help="只检查依赖和资源，不安装、不构建；默认检查 linux")
    parser.add_argument("--no-install", "-NoInstall", action="store_true", help="禁止自动安装 Python / JDK")
    parser.add_argument("--gecko-component-version", "-GeckoComponentVersion")
    parser.add_argument("--jcef-component-version", "-JcefComponentVersion")
    args = parser.parse_args(argv)
    if not args.target and args.check:
        args.target = "linux"
    if not args.target:
        if not sys.stdin.isatty():
            parser.print_help()
            return 0
        print("1 PC JAR\n2 Linux 完整包\n3 测试\n4 Android Debug\n5 Android Release\n6 安全清理\n7 浏览器内核\n8 一键打包 PC + Android 发行版\n0 退出")
        args.target = input("请选择: ").strip()
        if args.target == "0":
            return 0
    target = ALIASES.get(args.target, args.target)
    if target not in TASKS:
        parser.error("未知目标: " + target + "；Windows 安装包请在 Windows 使用 packaging/build.ps1 installer。")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.channel):
        parser.error("渠道仅允许 1-64 个字母、数字、下划线、连字符")
    try:
        jdk, source = preflight(ROOT, target, args)
        configure_java_environment(jdk)
        if args.check:
            print("[DONE] 环境检查通过")
            return 0
        env = dict(os.environ, JAVA_HOME=str(jdk))
        command = ["sh", ROOT / "gradlew", "--no-daemon", "--parallel", "--build-cache", "--configure-on-demand", f"-PonlineChannel={args.channel}"]
        for key in ("gecko", "jcef"):
            value = getattr(args, key + "_component_version")
            if value:
                if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", value):
                    raise ValueError("浏览器组件版本须为 x.y.z")
                command.append(f"-P{key}ComponentVersion={value}")
        run(command + TASKS[target], ROOT, env)
        if target != "clean":
            outputs = ("linux", "android-release") if target == "release-all" else (target,)
            for output in outputs:
                collect(ROOT, output, args, jdk, source)
        return 0
    except (ValueError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"[ERROR] {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
