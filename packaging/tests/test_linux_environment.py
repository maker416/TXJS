#!/usr/bin/env python3
# Copyright 2023-2025 RWPP contributors
# 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
# Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
# https://github.com/Minxyzgo/RWPP/blob/main/LICENSE

import importlib.util
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

PACKAGING = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("rwjs_linux_environment", PACKAGING / "build.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)
BASH = shutil.which("bash")


def executable(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)
    path.chmod(0o755)


@unittest.skipUnless(sys.platform.startswith("linux"), "Linux environment helpers")
class LinuxEnvironmentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="Linux environment tests ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.zip = self.root / "original.zip"
        with zipfile.ZipFile(self.zip, "w") as archive:
            for name in ("game-lib.jar", "assets/units/tank.ini", "libs/lwjgl.jar", "libs/slick.jar",
                         "liblwjgl64.so", "libopenal64.so", "libjinput-linux64.so"):
                archive.writestr(name, "fixture")

    def jdk(self, name, version="21.0.13", complete=True):
        home = self.root / name
        executable(home / "bin/java", f'#!/bin/sh\necho \'openjdk version "{version}"\' >&2\n')
        executable(home / "bin/javac", "#!/bin/sh\nexit 0\n")
        executable(home / "bin/jlink", "#!/bin/sh\nexit 0\n")
        if complete:
            (home / "jmods").mkdir()
            (home / "jmods/java.base.jmod").touch()
        return home

    def args(self, **updates):
        values = dict(game_archive=str(self.zip), check=False, no_install=False)
        values.update(updates)
        return SimpleNamespace(**values)

    def test_finds_full_jdk21_after_old_or_incomplete_installations(self):
        old, partial, good = self.jdk("JDK 17", "17.0.1"), self.jdk("partial JDK", complete=False), self.jdk("JDK 21")
        self.assertEqual(build.find_jdk(candidates=[old, partial, good]), good)
        with self.assertRaises(ValueError):
            build.find_jdk(candidates=[old, partial])

    def test_configures_only_process_environment_without_duplicate_path(self):
        jdk = self.jdk("JDK 21")
        with patch.dict(os.environ, {"JAVA_HOME": "old", "PATH": "/usr/bin"}):
            build.configure_java_environment(jdk)
            build.configure_java_environment(jdk)
            self.assertEqual(os.environ["JAVA_HOME"], str(jdk))
            self.assertEqual(os.get_exec_path().count(str(jdk / "bin")), 1)
            self.assertEqual(os.get_exec_path()[0], str(jdk / "bin"))

    def test_missing_jdk_installs_then_rediscovers_before_build(self):
        jdk = self.jdk("installed JDK")
        with patch.object(build, "find_jdk", side_effect=[ValueError("missing JDK"), jdk]), patch.object(build, "run") as installer:
            actual, source = build.preflight(self.root, "linux", self.args())
        self.assertEqual(actual, jdk)
        self.assertEqual(source, self.zip)
        installer.assert_called_once_with(["bash", self.root / "packaging/linux-environment.sh", "jdk"], self.root)

    def test_check_and_no_install_never_install_missing_jdk(self):
        for options in ({"check": True}, {"no_install": True}):
            with self.subTest(options=options):
                with patch.object(build, "find_jdk", side_effect=ValueError("missing JDK")), patch.object(build, "run") as installer:
                    with self.assertRaises(ValueError):
                        build.preflight(self.root, "linux", self.args(**options))
                    installer.assert_not_called()

    def test_bad_resources_do_not_trigger_jdk_installation(self):
        with patch.object(build, "find_jdk", side_effect=ValueError("missing JDK")), patch.object(build, "run") as installer:
            with self.assertRaises(ValueError):
                build.preflight(self.root, "linux", self.args(game_archive=str(self.root / "missing.zip")))
            installer.assert_not_called()

    def test_failed_installation_stops_preflight(self):
        with patch.object(build, "find_jdk", side_effect=ValueError("missing JDK")), patch.object(build, "run", side_effect=subprocess.CalledProcessError(1, "installer")):
            with self.assertRaises(subprocess.CalledProcessError):
                build.preflight(self.root, "linux", self.args())

    def stub_packages(self, backend):
        binary = self.root / "stub bin"
        capture = self.root / "install.log"
        ready = self.root / "python-ready"
        executable(binary / "sudo", '#!/bin/sh\nif [ "$1" = "-n" ]; then shift; fi\nexec "$@"\n')
        executable(binary / backend, '''#!/bin/sh
printf '%s\n' "$*" >> "$RWJS_INSTALL_CAPTURE"
if [ "${RWJS_INSTALL_FAIL:-}" = '1' ]; then exit 9; fi
if [ "$1" = 'install' ]; then : > "$RWJS_BOOTSTRAP_READY"; fi
''')
        env = dict(os.environ, PATH=str(binary), RWJS_INSTALL_CAPTURE=str(capture), RWJS_BOOTSTRAP_READY=str(ready))
        return binary, capture, ready, env

    def test_apt_and_dnf_install_only_requested_packages(self):
        for backend in ("apt-get", "dnf"):
            with self.subTest(backend=backend):
                # 每个后端使用独立目录，PATH 不包含系统包管理器。
                with tempfile.TemporaryDirectory(dir=self.root) as temp:
                    original = self.root
                    self.root = Path(temp)
                    try:
                        _, capture, _, env = self.stub_packages(backend)
                        result = subprocess.run([BASH, str(PACKAGING / "linux-environment.sh"), "jdk"], env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True)
                        self.assertEqual(result.returncode, 0, result.stderr)
                        lines = capture.read_text().splitlines()
                        if backend == "apt-get":
                            self.assertEqual(lines, ["update", "install -y openjdk-21-jdk"])
                        else:
                            self.assertEqual(lines, ["install -y java-21-openjdk-devel java-21-openjdk-jmods"])
                    finally:
                        self.root = original

    def test_python_bootstrap_respects_read_only_and_help(self):
        binary, capture, ready, env = self.stub_packages("apt-get")
        (binary / "dirname").symlink_to(shutil.which("dirname"))
        (binary / "bash").symlink_to(BASH)
        executable(binary / "python3", f'''#!/bin/sh
if [ "$1" = '-c' ]; then [ -f "$RWJS_BOOTSTRAP_READY" ]; exit $?; fi
exec {shlex.quote(sys.executable)} "$@"
''')
        for flag, code in (("--check", 1), ("--no-install", 1), ("--help", 0)):
            with self.subTest(flag=flag):
                result = subprocess.run([BASH, str(PACKAGING / "build.sh"), flag], env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True)
                self.assertEqual(result.returncode, code, result.stdout + result.stderr)
                self.assertFalse(capture.exists())
        # 参数校验会在预检前失败，确保本用例只测试 Python 引导，不执行 Gradle。
        result = subprocess.run([BASH, str(PACKAGING / "build.sh"), "desktop", "--channel", "invalid/channel"], env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertTrue(ready.exists())
        self.assertEqual(capture.read_text().splitlines(), ["update", "install -y python3"])

    def test_old_python_on_path_falls_back_without_installation(self):
        binary, capture, _, env = self.stub_packages("apt-get")
        env["PATH"] += os.pathsep + os.defpath
        executable(binary / "python3", "#!/bin/sh\nexit 1\n")
        result = subprocess.run([BASH, str(PACKAGING / "build.sh"), "--help"], env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--channel", result.stdout)
        self.assertFalse(capture.exists())


if __name__ == "__main__":
    unittest.main()
