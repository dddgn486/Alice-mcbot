"""工件**内容摘要**：给"Gradle 打出来的 jar 不可字节复现"这个事实用的。

**为什么需要它**（2026-09-17 实测）：同一份源码连编两次得到**不同** sha256
（`862ef1e7…` → `9a8a7309…`，只有 MANIFEST/条目顺序/时间戳在变）。
于是"文件 sha256 一致"**只能**证明"当时同步的是同一个文件"，
**不能**回答"我客户端里的 jar 是不是现在这版代码" —— 重新构建一次哈希就变了。

**做法**：对 jar 内**按名字排序**的每个条目（跳过目录与 `META-INF/MANIFEST.MF`）
取 `名字 + \\0 + 内容 sha256` 拼成规范流，再取 sha256 ⇒ 同一份代码**恒等**，不同代码**必不同**。

用法：
  tools/jar-content-hash.sh [jar]                 # 打印条目数与内容摘要
  tools/jar-content-hash.sh --compare A.jar B.jar # 逐条目比对（回答"客户端是不是最新"）
"""
from __future__ import annotations

import hashlib
import pathlib
import sys
import zipfile

SKIP_PREFIX = ("META-INF/MANIFEST.MF",)


def digest(path: pathlib.Path) -> tuple[str, int, dict[str, str]]:
    entries: dict[str, str] = {}
    with zipfile.ZipFile(path) as z:
        for name in sorted(z.namelist()):
            if name.endswith("/") or any(name.startswith(p) for p in SKIP_PREFIX):
                continue
            entries[name] = hashlib.sha256(z.read(name)).hexdigest()
    stream = hashlib.sha256()
    for name in sorted(entries):
        stream.update(name.encode("utf-8"))
        stream.update(b"\0")
        stream.update(entries[name].encode("ascii"))
        stream.update(b"\n")
    return stream.hexdigest(), len(entries), entries


def main() -> int:
    args = [a for a in sys.argv[1:]]
    if args and args[0] == "--compare":
        if len(args) != 3:
            print("用法：jar-content-hash.py --compare A.jar B.jar")
            return 2
        a, b = pathlib.Path(args[1]), pathlib.Path(args[2])
        for p in (a, b):
            if not p.exists():
                print(f"CONTENT_HASH_RESULT FAIL: 找不到 {p}")
                return 1
        da, na, ea = digest(a)
        db, nb, eb = digest(b)
        only_a = sorted(set(ea) - set(eb))
        only_b = sorted(set(eb) - set(ea))
        diff = [n for n in sorted(set(ea) & set(eb)) if ea[n] != eb[n]]
        print(f"  A={a.name} entries={na} content_sha256={da[:16]}…")
        print(f"  B={b.name} entries={nb} content_sha256={db[:16]}…")
        if da == db:
            print(f"CONTENT_HASH_RESULT SAME: 两份工件**内容一致**（{na} 条目，逐条目零差异）")
            return 0
        print(f"CONTENT_HASH_RESULT DIFFER: 仅 A 有 {len(only_a)} / 仅 B 有 {len(only_b)} / 内容不同 {len(diff)}")
        for label, items in (("仅 A", only_a), ("仅 B", only_b), ("不同", diff)):
            for n in items[:8]:
                print(f"    [{label}] {n}")
        return 1
    jar = pathlib.Path(args[0]) if args else pathlib.Path("build/libs/alice-1.0.0-1.20.1.jar")
    if not jar.exists():
        print(f"CONTENT_HASH_RESULT FAIL: 找不到 {jar}")
        return 1
    d, n, _ = digest(jar)
    print(f"  jar={jar} entries={n}")
    print(f"JAR_CONTENT_SHA256={d}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
