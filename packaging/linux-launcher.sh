#!/usr/bin/env bash
# Copyright 2023-2025 RWPP contributors
# 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
# Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
# https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
set -euo pipefail
cd -- "$(dirname -- "$(readlink -f -- "${BASH_SOURCE[0]}")")"
mkdir -p logs mods/units mods/maps extension generated_lib
export LD_LIBRARY_PATH="$PWD${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec ./runtime/bin/java \
    -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8 \
    -XX:ErrorFile=logs/hs_err_pid%p.log -Djava.library.path="$PWD" \
    --add-opens=java.base/java.net=ALL-UNNAMED \
    --add-opens=java.desktop/sun.awt=ALL-UNNAMED \
    -cp 'app/*:generated_lib/*:extension/*:libs/*' \
    io.github.rwpp.desktop.MainKt -native "$@"
