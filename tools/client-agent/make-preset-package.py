#!/usr/bin/env python3
"""从 presets/<id>/ 重建桌面版可导入的 `.dshpreset` 包（zip：manifest.json + preset/**）。

为什么需要它（2026-09-24 实测）：`tools/make-cloud-tunnel-bundle.sh` 只**拷贝** `.dshpreset`，
**不会重建** ⇒ 改了人设却忘了重建包，桌面版导入到的仍是旧人设 —— 而这种偏差是**静默**的
（包仍在、能导入、只是内容旧）。

用法：`python3 tools/client-agent/make-preset-package.py`
产物：`tools/client-agent/alice-client-master.dshpreset`（原地覆盖，仓库内跟踪）

`exportedAt` 取**源文件的最后修改时间**（而不是"现在"）⇒ 只要人设没变，重建出的包**字节相同**，
仓库不会因为跑一次打包就产生无意义 diff。
"""
import json
import pathlib
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
PRESET_ID = "alice-client-master"
SRC = HERE / "presets" / PRESET_ID
OUT = HERE / f"{PRESET_ID}.dshpreset"
SOURCE_DSH_VERSION = "0.1.5-rc.3"
META = {
    "name": "Alice 客户端管家",
    "description": "运行在玩家 Windows 机器上的本地 agent：查日志/截图、按请求上传测试数据、"
                   "执行云端要求的客户端调整；不改源码。",
}
# 包内只放这两样（与既有包一致；preset.yml 是元数据、agent.cordis.yml 是人设/工具组合）
WANTED = ("preset.yml", "agent.cordis.yml")


def main() -> int:
    missing = [n for n in WANTED if not (SRC / n).is_file()]
    if missing:
        print(f"✗ {SRC} 里缺 {missing}")
        return 2

    newest = max((SRC / n).stat().st_mtime for n in WANTED)
    stamp = __import__("datetime").datetime.fromtimestamp(
        newest, __import__("datetime").timezone.utc
    ).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    manifest = {
        "format": "dsh-preset",
        "version": 1,
        "id": PRESET_ID,
        "name": META["name"],
        "description": META["description"],
        "exportedAt": stamp,
        "sourceDshVersion": SOURCE_DSH_VERSION,
    }

    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        zi = zipfile.ZipInfo("manifest.json", date_time=(1980, 1, 1, 0, 0, 0))
        z.writestr(zi, json.dumps(manifest, ensure_ascii=False, indent=2))
        for name in WANTED:
            z.write(SRC / name, f"preset/{name}")

    with zipfile.ZipFile(OUT) as z:
        names = z.namelist()
        print(f"→ 已重建 {OUT.name}（{OUT.stat().st_size} 字节）")
        for n in names:
            print(f"    {n}  {z.getinfo(n).file_size} 字节")
        print(f"    manifest.exportedAt = {stamp}（源文件 mtime ⇒ 人设没变则字节不变）")
        if sorted(names) != sorted(["manifest.json", *(f"preset/{n}" for n in WANTED)]):
            print("✗ 包内条目与预期不符")
            return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
