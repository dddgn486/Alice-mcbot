#!/usr/bin/env python3
"""生成 Alice 观测用**矿石透视资源包**（纯资源包，不装模组）。

为什么要这个包（用户 2026-09-20 需求，两轮迭代后的定稿口径）：
  · 真实地形实测挖矿行为时，要在**不改变服务端任何状态**的前提下看见矿脉与地形结构；
  · ⚠️ **第一版做成"完全隐形"被否**：那样会失去空间感（分不清山体/空洞/墙面）；
  · 定稿：**带边框的透明玻璃** —— 围岩半透明可见轮廓，**矿石保持原样**；
  · 「相连部分无边框」为什么能成立：相邻围岩之间的面在**引擎层本来就被剔除**
    （`BlockState` 遮挡判定与模型无关 ⇒ 覆盖模型不影响它）⇒ 边框只出现在**外表面**，
    内部不会糊成网格。<b>完整版</b>"只在外轮廓画边"（相邻处连边框都不画）需要 CTM 类模组
    （Forge 1.20.1 的 Fusion），纯资源包做不到逐边选择 —— 见 `--help` 输出与文档。

⭐ **必须按 blockstate 枚举模型状态**（2026-09-20 真机截图暴露的坑）：原版很多方块的 blockstate 是
**多个加权变体**，例如 `stone` = `stone` / `stone_mirrored` / 各自 y=180 ⇒ 只覆盖 `stone.json`
会让**一半的石头**保持不透明（用户看到的就是"只有部分石头透明"）。`deepslate` 同理
（`deepslate` / `deepslate_mirrored`）。⇒ 本脚本**从客户端 jar 里读 blockstate**，把每个引用到的
模型**全部**覆盖；读不到 jar 时回落到"同名 + `_mirrored`"并**响亮告警**（不许静默出半个包）。

为什么用脚本而不是手搓文件：① 可复现（客户端目录丢了重跑一次）；② 材质颜色表是数据；
③ 透明 PNG 手写（不依赖 PIL，任何环境都能跑）。

用法：
    python3 tools/gen-xray-pack.py [客户端实例目录]
默认客户端目录 = /mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10
"""
from __future__ import annotations

import json
import pathlib
import struct
import sys
import zlib

DEFAULT_CLIENT = "/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10"
PACK_NAME = "AliceXray"
PACK_FORMAT = 15          # 1.20.1
BORDER_ALPHA = 150        # 外框不透明度（0..255）—— 看得见轮廓，但不会挡视线
INNER_ALPHA = 40          # 紧贴外框内侧的一圈淡光（玻璃感）

# 材质分组：方块名 → 边框颜色（RGB）。颜色即"这里是什么"的线索（石头灰、泥土棕、沙黄…）
MATERIALS: dict[str, tuple[int, int, int]] = {}


def assign(color: tuple[int, int, int], names: str) -> None:
    for name in names.split():
        MATERIALS[name] = color


assign((150, 150, 150), "stone cobblestone mossy_cobblestone smooth_stone stone_bricks "
                        "cracked_stone_bricks mossy_stone_bricks chiseled_stone_bricks "
                        "granite diorite andesite polished_granite polished_diorite polished_andesite "
                        "gravel tuff calcite dripstone_block clay")
assign((80, 80, 88), "deepslate cobbled_deepslate polished_deepslate deepslate_bricks deepslate_tiles "
                     "chiseled_deepslate blackstone polished_blackstone basalt smooth_basalt obsidian")
assign((120, 85, 55), "dirt coarse_dirt rooted_dirt podzol mycelium soul_soil")
assign((95, 140, 60), "grass_block moss_block")
assign((215, 200, 150), "sand red_sand sandstone smooth_sandstone red_sandstone end_stone")
assign((150, 80, 80), "netherrack magma_block")
assign((170, 210, 235), "snow_block ice packed_ice blue_ice")
assign((110, 100, 130), "soul_sand")


def png_rgba(path: pathlib.Path, size: int, pixel) -> None:
    """写一张 RGBA PNG（`pixel(x, y) -> (r, g, b, a)`）——手写 PNG，不依赖 PIL。"""
    raw = bytearray()
    for y in range(size):
        raw.append(0)                      # filter type 0
        for x in range(size):
            raw.extend(pixel(x, y))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
                     + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def frame_texture(size: int, color: tuple[int, int, int]):
    """透明玻璃 + 1px 外框 + 内侧一圈淡光（**外表面**才看得到，内部面被引擎剔除）。"""
    def pixel(x: int, y: int) -> tuple[int, int, int, int]:
        on_border = x == 0 or y == 0 or x == size - 1 or y == size - 1
        if on_border:
            return color + (BORDER_ALPHA,)
        on_inner = x == 1 or y == 1 or x == size - 2 or y == size - 2
        if on_inner:
            return color + (INNER_ALPHA,)
        return (0, 0, 0, 0)
    return pixel


def models_referenced_by(block: str, jar: pathlib.Path) -> list[str]:
    """从客户端 jar 的 blockstate 里取出该方块**引用到的全部模型**（去掉命名空间/目录）。

    ⚠️ 返回值可能含**不属于 `MATERIALS` 的名字**（如 `stone_mirrored`、`grass_block_snow`）——
    那正是要覆盖的对象：它们同属这个方块的材质分组。
    """
    import re
    import zipfile
    entry = f"assets/minecraft/blockstates/{block}.json"
    with zipfile.ZipFile(jar) as archive:
        if entry not in archive.namelist():
            return []
        text = archive.read(entry).decode("utf-8", "replace")
    refs = {ref.split(":")[-1].split("/")[-1] for ref in re.findall(r'"model"\s*:\s*"([^"]+)"', text)}
    # 变体可能引用**别的方块**的模型（如 grass_block_snow 属于 grass_block）⇒ 一并覆盖，颜色随本方块
    return sorted(refs)


def find_client_jar(client: pathlib.Path) -> pathlib.Path | None:
    """定位**含原版资源**的 jar（Forge 版本 jar 里有 `assets/minecraft/blockstates/`）。"""
    candidates = sorted(client.glob("*.jar")) + sorted(client.glob("PCL/**/*.jar"))
    for candidate in candidates:
        try:
            import zipfile
            with zipfile.ZipFile(candidate) as archive:
                if "assets/minecraft/blockstates/stone.json" in archive.namelist():
                    return candidate
        except Exception:
            continue
    return None


def main() -> int:
    client = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_CLIENT)
    root = client / "resourcepacks" / PACK_NAME
    models = root / "assets" / "minecraft" / "models" / "block"
    textures = root / "assets" / "alicexray" / "textures" / "block"
    for directory in (models, textures):
        if directory.exists():
            for stale in directory.iterdir():
                stale.unlink()              # 幂等：重跑即覆盖（避免旧的全透明贴图残留）
        directory.mkdir(parents=True, exist_ok=True)

    (root / "pack.mcmeta").write_text(json.dumps({
        "pack": {"pack_format": PACK_FORMAT,
                 "description": "Alice 观测包：围岩=带边框透明玻璃（矿石保留原样）。"
                                "相连处不画边框（引擎剔除内部面）；完整版需 CTM 模组。"}
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    colors = sorted({color for color in MATERIALS.values()})
    texture_of: dict[tuple[int, int, int], str] = {}
    for index, color in enumerate(colors):
        name = f"xray_frame_{index}"
        png_rgba(textures / f"{name}.png", 16, frame_texture(16, color))
        texture_of[color] = f"alicexray:block/{name}"

    jar = find_client_jar(client)
    covered: set[str] = set()
    state_models = 0
    for block, color in MATERIALS.items():
        names = models_referenced_by(block, jar) if jar else []
        if not names:
            # 回落：同名 + `_mirrored`（`stone`/`deepslate` 那一半不透明就是这么漏掉的）
            names = [block, f"{block}_mirrored"]
            if jar:
                print(f"⚠️ {block}：blockstate 里没解析出模型 ⇒ 回落到 {names}")
        state_models += len(names)
        for name in names:
            (models / f"{name}.json").write_text(json.dumps({
                "parent": "minecraft:block/cube_all",
                "render_type": "minecraft:cutout_mipped",
                "textures": {"all": texture_of[color], "particle": texture_of[color]}
            }, indent=2), encoding="utf-8")
            covered.add(name)
    if jar is None:
        print("⚠️ 没找到含原版资源的客户端 jar ⇒ 只覆盖了「同名 + _mirrored」；"
              "若截图里仍有不透明围岩，请把客户端实例目录作为参数传入。")

    print(f"包路径      = {root}")
    print(f"隐形围岩数  = {len(MATERIALS)} 种方块（保留矿石原样）")
    print(f"覆盖模型数  = {len(covered)}（含 blockstate 的**全部变体状态**：{len(covered) - len(MATERIALS)} 个"
          f"非同名模型，如 stone_mirrored / deepslate_mirrored / grass_block_snow）")
    print(f"原版 jar    = {jar.name if jar else '未找到（回落模式）'}")
    print(f"边框贴图数  = {len(colors)}（按材质上色：灰=石/棕=土/黄=沙/红=下界岩/蓝=冰…）")
    print(f"pack_format = {PACK_FORMAT}（1.20.1）")
    print("启用方式    = 选项→资源包 勾选 AliceXray（或 options.txt 的 resourcePacks 里加 file/AliceXray）")
    print("⚠️ 完整版「只在外轮廓画边」需要 CTM 模组（Forge 1.20.1：Fusion）——纯资源包只能做到"
          "「内部无边框 + 外表面淡网格」，因为逐边选贴图不在原版资源包能力内。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
