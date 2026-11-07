#!/usr/bin/env bash
# Copyright 2023-2025 RWPP contributors
# 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
# Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
# https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
set -euo pipefail

rw_dependency="${1:-}"
case "$rw_dependency" in
    python|jdk) ;;
    *) echo '[ERROR] 环境安装目标必须是 python 或 jdk。' >&2; exit 1 ;;
esac

rw_run_as_root() {
    if (( EUID == 0 )); then
        "$@"
    elif command -v sudo >/dev/null 2>&1; then
        if [[ -t 0 ]]; then
            sudo "$@"
        else
            # 无交互终端时不等待密码，直接返回明确的安装失败。
            sudo -n "$@"
        fi
    else
        echo '[ERROR] 自动安装需要 root 或 sudo；请手动安装 Python 3.9+ / 完整 JDK 21。' >&2
        return 1
    fi
}

if command -v apt-get >/dev/null 2>&1; then
    rw_packages=(python3)
    if [[ "$rw_dependency" == jdk ]]; then rw_packages=(openjdk-21-jdk); fi
    echo "[ENV] 通过 apt-get 安装 ${rw_packages[*]}（需要 root/sudo 和网络）"
    rw_run_as_root apt-get update
    rw_run_as_root apt-get install -y "${rw_packages[@]}"
elif command -v dnf >/dev/null 2>&1; then
    rw_packages=(python3)
    if [[ "$rw_dependency" == jdk ]]; then rw_packages=(java-21-openjdk-devel java-21-openjdk-jmods); fi
    echo "[ENV] 通过 dnf 安装 ${rw_packages[*]}（需要 root/sudo 和网络）"
    rw_run_as_root dnf install -y "${rw_packages[@]}"
else
    echo '[ERROR] 未找到支持的包管理器（apt-get / dnf）。请手动安装 Python 3.9+ 和完整 JDK 21 后重试。' >&2
    exit 1
fi
