# Copyright 2023-2025 RWPP contributors
# 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束，可以在以下链接找到该许可证。
# Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
# https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
"""Build a self-contained example; all personalized content is inside the pack."""
import argparse
import struct
import uuid
import zlib
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED


def background(color):
    width, height = 960, 180
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            stripe = 0.16 if ((x + y) // 28) % 2 else 0
            factor = (1 - x / width) * 0.35 + 0.14 + stripe
            raw.extend(int(channel * factor) for channel in color)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pack-id", required=True, type=uuid.UUID)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    output = root / "build" / "artifacts" / "personalized-player"
    output.mkdir(parents=True, exist_ok=True)
    manifest = f'''[theme]
id = "personalized-player"
name = "玩家专属外观示例"
author = "RWJS contributors"
version = "1.0.0"
description = "平台分配的背景、边框和支持者装饰；未核验玩家保持默认。"

[room.player]
backgroundColor = "#182533"
borderColor = "#456078"
corner = 8

[personalization]
schemaVersion = 1
platformPackId = "{args.pack_id}"

[personalization.slots."room.player.background".variants.gold]
image = "players/gold.png"

[personalization.slots."room.player.background".variants.blue]
image = "players/blue.png"

[personalization.slots."room.player.frame".variants.gold]
borderColor = "#FFD166"
borderWidth = 3

[[personalization.slots."room.player.badges".variants.supporter.content.rows]]
cells = [{{ text = "美术包支持者", contentColor = "#FFD166", backgroundColor = "#332B18" }}]

[[personalization.slots."room.player.info".variants.author.content.rows]]
cells = [{{ text = "此美术包的作者", contentColor = "#B8D8FF" }}]
'''
    target = output / "personalized-player.rwtheme"
    with ZipFile(target, "w", ZIP_DEFLATED) as archive:
        archive.writestr("theme.toml", manifest.encode("utf-8"))
        archive.writestr("players/gold.png", background((255, 209, 102)))
        archive.writestr("players/blue.png", background((110, 175, 255)))
    print(target)


if __name__ == "__main__":
    main()
