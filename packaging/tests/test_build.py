#!/usr/bin/env python3
# Copyright 2023-2025 RWPP contributors
# 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
# Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
# https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
"""打包集成验证使用假 Gradle/JDK，不代替实际客户端编译或运行测试。"""

import hashlib
import importlib.util
import io
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

PACKAGING = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("rwjs_build", PACKAGING / "build.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


def write(path, content, executable=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    if executable:
        path.chmod(0o755)


def make_zip(path, prefix="Rusted Warfare/"):
    included = ["game-lib.jar", "assets/units/tank.ini", "res/image.png", "font/font.ttf",
                "libs/lwjgl.jar", "libs/slick.jar", "liblwjgl64.so", "libopenal64.so",
                "libjinput-linux64.so", "libRocketCore.so.1"]
    excluded = ["saves/private.rwsave", "mods/units/private.ini", "jvm-linux/bin/java",
                "preferences.ini", "RWJS.exe", "libs/android.jar", "libs/android-game-lib.jar",
                "generated_lib/old.jar", "assets/private.log"]
    with zipfile.ZipFile(path, "w") as archive:
        for name in included + excluded:
            archive.writestr(prefix + name, name)
    return included, excluded


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="打包 ZIP tests ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.zip = self.root / "原版 with spaces.zip"

    def test_extract_flat_wrapped_and_backslash_archive(self):
        for index, prefix in enumerate(("", "Rusted Warfare/", "Rusted Warfare\\")):
            with self.subTest(prefix=prefix):
                included, excluded = make_zip(self.zip, prefix)
                before = build.file_hash(self.zip)
                dest = self.root / str(index)
                dest.mkdir()
                build.extract_payload(self.zip, dest, linux=True)
                self.assertEqual(set(included), {p.relative_to(dest).as_posix() for p in dest.rglob("*") if p.is_file()})
                self.assertEqual(build.file_hash(self.zip), before)
                self.assertFalse(any((dest / p).exists() for p in excluded))

    def test_rejects_traversal_absolute_and_drive_paths(self):
        for name in ("../escape", "/etc/file", "assets/../../escape", "C:/escape", "assets\\..\\escape", "assets/./bad"):
            with self.subTest(name=name):
                make_zip(self.zip)
                with zipfile.ZipFile(self.zip, "a") as archive:
                    archive.writestr(name, "bad")
                with zipfile.ZipFile(self.zip) as archive, self.assertRaises(ValueError):
                    build.archive_plan(archive)

    def test_rejects_symlink_and_case_conflict(self):
        for symlink in (True, False):
            make_zip(self.zip)
            with zipfile.ZipFile(self.zip, "a") as archive:
                if symlink:
                    entry = zipfile.ZipInfo("Rusted Warfare/assets/link")
                    entry.create_system = 3
                    entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                    archive.writestr(entry, "/tmp")
                else:
                    archive.writestr("Rusted Warfare/assets/units/TANK.INI", "conflict")
            with zipfile.ZipFile(self.zip) as archive, self.assertRaises(ValueError):
                build.archive_plan(archive)

    def test_rejects_missing_or_multiple_roots(self):
        for names in (("game-lib.jar",), ("a/game-lib.jar", "a/assets/file", "b/game-lib.jar", "b/assets/file")):
            with zipfile.ZipFile(self.zip, "w") as archive:
                for name in names:
                    archive.writestr(name, "fixture")
            with zipfile.ZipFile(self.zip) as archive, self.assertRaises(ValueError):
                build.archive_plan(archive)

    def test_linux_requires_native_libraries(self):
        with zipfile.ZipFile(self.zip, "w") as archive:
            archive.writestr("game-lib.jar", "fixture")
            archive.writestr("assets/file", "fixture")
        with zipfile.ZipFile(self.zip) as archive, self.assertRaisesRegex(ValueError, "Linux x64"):
            build.archive_plan(archive, linux=True)

    def test_source_priority_and_relative_paths(self):
        write(self.root / "packaging/game-archive.local.txt", '# comment\n"原版 with spaces.zip"\n')
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(build.resolve_archive(self.root), self.zip)
            with patch.dict(os.environ, {"RW_GAME_ARCHIVE": str(self.root / "env.zip")}):
                self.assertEqual(build.resolve_archive(self.root), self.root / "env.zip")
                self.assertEqual(build.resolve_archive(self.root, "explicit.zip"), self.root / "explicit.zip")

    def test_split_reassembles_to_signed_apk_bytes_and_checksum(self):
        apk = self.root / "signed.apk"
        apk.write_bytes(bytes(range(256)) * 40)
        before = build.file_hash(apk)
        build.split_update(apk, self.root, "RWJS-Android", part_size=1024)
        parts = sorted(self.root.glob("RWJS-Android.zip.[0-9]*"))
        self.assertGreater(len(parts), 2)
        self.assertTrue(all(p.stat().st_size <= 1024 for p in parts))
        merged = b"".join(p.read_bytes() for p in parts)
        self.assertEqual(hashlib.sha256(merged).hexdigest(), (self.root / "RWJS-Android.zip.sha256").read_text().split()[0])
        with zipfile.ZipFile(io.BytesIO(merged)) as archive:
            self.assertEqual(archive.read("signed.apk"), apk.read_bytes())
        self.assertEqual(build.file_hash(apk), before)
        self.assertFalse((self.root / "RWJS-Android.zip").exists())


@unittest.skipUnless(os.name == "posix", "Linux shell integration")
class BuildIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="Linux 打包 integration ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "packaging").mkdir()
        for name in ("build.py", "build.sh", "linux-launcher.sh", "linux-environment.sh"):
            shutil.copy2(PACKAGING / name, self.root / "packaging" / name)
        write(self.root / "build.gradle.kts", 'version = "1.2.3"\n')
        write(self.root / "LICENSE", "license fixture")
        write(self.root / "rwpp-desktop/logo.png", "icon fixture")
        sdk = self.root / "Android SDK"
        (sdk / "platforms").mkdir(parents=True)
        (sdk / "build-tools").mkdir()
        write(self.root / "local.properties", f"sdk.dir={sdk}\n")
        self.jdk = self.root / "JDK 21"
        (self.jdk / "jmods").mkdir(parents=True)
        (self.jdk / "jmods/java.base.jmod").touch()
        java = '''#!/bin/sh
if [ "$1" = '-version' ]; then
    echo 'openjdk version "21.0.8"' >&2
else
    printf '%s\\n' "$PWD" "$@" > "$RWJS_TEST_CAPTURE"
fi
'''
        write(self.jdk / "bin/java", java, True)
        write(self.jdk / "bin/javac", "#!/bin/sh\nexit 0\n", True)
        write(self.jdk / "bin/jlink", '''#!/bin/sh
while [ "$1" != '--output' ]; do shift; done
mkdir -p "$2/bin"
cp "$JAVA_HOME/bin/java" "$2/bin/java"
''', True)
        write(self.root / "gradlew", '''#!/bin/sh
printf '%s\\n' "$@" > gradle-args.txt
if [ "${RWJS_TEST_FAIL:-}" = '1' ]; then exit 7; fi
mkdir -p rwpp-desktop/build/compose/jars
printf 'fat jar fixture' > rwpp-desktop/build/compose/jars/RWJS-linux-x64-1.2.3-release.jar
''', True)
        self.zip = self.root / "原版 game.zip"
        self.included, self.excluded = make_zip(self.zip)
        write(self.root / "packaging/game-archive.local.txt", str(self.zip))
        self.env = {k: v for k, v in os.environ.items() if k not in {"RW_GAME_ARCHIVE", "JITPACK", "RWJS_TEST_FAIL", "RWJS_TEST_CAPTURE"}}
        self.env["JAVA_HOME"] = str(self.jdk)

    def invoke(self, *args):
        return subprocess.run(["bash", str(self.root / "packaging/build.sh"), *args],
                              cwd="/tmp", env=self.env, capture_output=True, text=True)

    def test_complete_bundle_and_launcher_from_external_cwd(self):
        result = self.invoke("installer", "--channel", "test_cn")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        dest = self.root / "build/artifacts/linux-test_cn"
        package = dest / "RWJS-Linux-x64-1.2.3.tar.gz"
        self.assertEqual(build.file_hash(package), (dest / (package.name + ".sha256")).read_text().split()[0])
        with tarfile.open(package) as archive:
            names = set(archive.getnames())
            self.assertTrue(all("RWJS/" + n in names for n in self.included))
            self.assertFalse(any("RWJS/" + n in names for n in self.excluded))
            self.assertTrue(archive.getmember("RWJS/RWJS.sh").mode & 0o111)
            self.assertTrue(archive.getmember("RWJS/runtime/bin/java").mode & 0o111)
            self.assertIn("RWJS/app/RWJS.jar", names)
            self.assertIn("RWJS/LICENSE", names)
        capture = self.root / "capture.txt"
        env = dict(self.env, RWJS_TEST_CAPTURE=str(capture))
        link = self.root / "launcher symlink.sh"
        link.symlink_to(dest / "RWJS/RWJS.sh")
        subprocess.run([str(link), "argument with spaces"], cwd="/tmp", env=env, check=True)
        content = capture.read_text().splitlines()
        self.assertEqual(content[0], str(dest / "RWJS"))
        self.assertIn("app/*:generated_lib/*:extension/*:libs/*", content)
        self.assertEqual(content[-2:], ["-native", "argument with spaces"])
        self.assertIn("-PonlineChannel=test_cn", (self.root / "gradle-args.txt").read_text())

    def test_failed_gradle_preserves_previous_artifact(self):
        previous = self.root / "build/artifacts/linux-official/previous.txt"
        write(previous, "keep")
        self.env["RWJS_TEST_FAIL"] = "1"
        result = self.invoke("linux")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(previous.read_text(), "keep")

    def test_check_and_invalid_channel_never_run_gradle(self):
        self.assertEqual(self.invoke("linux", "--check").returncode, 0)
        self.assertNotEqual(self.invoke("linux", "--channel", "../bad").returncode, 0)
        self.assertFalse((self.root / "gradle-args.txt").exists())

    def test_adjacent_linux_zip_overrides_other_machine_settings(self):
        adjacent = self.root / "build/Rusted-Warfare-linux.zip"
        adjacent.parent.mkdir(parents=True)
        shutil.copy2(self.zip, adjacent)
        self.env["RW_GAME_ARCHIVE"] = "C:/old-machine/Rusted-Warfare-win.zip"
        write(self.root / "packaging/game-archive.local.txt", "/old/linux/path.zip\n")
        result = self.invoke("--check")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(str(adjacent), result.stdout)
        result = self.invoke("linux", "--check", "--game-archive", str(self.zip))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(str(self.zip), result.stdout)

    def test_stale_java_home_falls_back_to_path_and_sets_gradle_environment(self):
        java_bin = self.root / "path bin"
        java_bin.mkdir()
        (java_bin / "java").symlink_to(self.jdk / "bin/java")
        self.env["PATH"] = str(java_bin) + os.pathsep + self.env["PATH"]
        self.env["JAVA_HOME"] = "C:/old-machine/JDK"
        result = self.invoke("linux", "--check", "--no-install")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("JAVA_HOME = " + str(self.jdk), result.stdout)

    def test_pc_build_does_not_require_android_sdk(self):
        write(self.root / "local.properties", r"sdk.dir=C\:\\Users\\Maker\\Android\\Sdk" + "\n")
        for target in ("linux", "desktop", "test"):
            with self.subTest(target=target):
                result = self.invoke(target, "--check")
                self.assertEqual(result.returncode, 0, result.stderr)
        (self.root / "local.properties").unlink()
        self.assertEqual(self.invoke("linux", "--check").returncode, 0)
        self.assertFalse((self.root / "gradle-args.txt").exists())

    def test_android_targets_diagnose_old_windows_sdk(self):
        write(self.root / "local.properties", r"sdk.dir=C\:\\Users\\Maker\\Android\\Sdk" + "\n")
        for target in ("android-debug", "android-release", "browser-kernels"):
            with self.subTest(target=target):
                result = self.invoke(target, "--check")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Android SDK", result.stderr)
        self.assertFalse((self.root / "gradle-args.txt").exists())


if __name__ == "__main__":
    unittest.main()
