#!/usr/bin/env bash
# Copyright 2023-2025 RWPP contributors
# 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
# Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
# https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
rw_read_only=0
rw_help=0
for rw_argument in "$@"; do
    case "$rw_argument" in
        --check|-Check|--no-install|-NoInstall) rw_read_only=1 ;;
        --help|-h|help) rw_help=1 ;;
    esac
done
rw_python=''
rw_find_python() {
    local rw_candidate
    rw_python=''
    while IFS= read -r rw_candidate; do
        if "$rw_candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)' >/dev/null 2>&1; then
            rw_python="$rw_candidate"
            return 0
        fi
    done < <(type -a -P python3 2>/dev/null || true)
    return 1
}
if ! rw_find_python; then
    if (( rw_help )); then
        echo '用法: build.sh [linux|desktop|release-all|android-debug|android-release|test|clean] [--check] [--no-install]'
        echo '正常构建自动配置 Python 3.9+ 和 JDK 21；--check / --no-install 禁止安装。'
        exit 0
    fi
    if (( rw_read_only )); then
        echo '[ERROR] 缺少 Python 3.9+；检查/禁止安装模式不会安装软件。' >&2
        exit 1
    fi
    bash "$script_dir/linux-environment.sh" python
    hash -r
    if ! rw_find_python; then
        echo '[ERROR] 安装后仍未找到 Python 3.9+；请检查发行版的软件包版本。' >&2
        exit 1
    fi
fi
exec "$rw_python" "$script_dir/build.py" "$@"
