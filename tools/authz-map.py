#!/usr/bin/env python3
"""授权/审批框架总览生成器（零依赖，只用标准库）。

单一出处：``docs/authz/AUTHZ_REGISTRY.csv``（Excel 可直接打开/批注）。
产出（全部会被覆盖，不要手改）：

* ``docs/authz/OVERVIEW.md`` —— 单页总览（四问速查 + 六层统计 + 闸门明细表）
* ``docs/authz/flow.svg``    —— 分层流程图（纯手写 SVG，无渲染器依赖）
* ``docs/authz/index.html``  —— 自包含页面：内嵌 SVG + 可筛选/搜索的完整表格

用法：``bash tools/authz-map.sh``（等价 ``python3 tools/authz-map.py``）
"""
from __future__ import annotations

import csv
import html
import os
import re
import sys
from datetime import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "authz")
REGISTRY = os.path.join(OUT, "AUTHZ_REGISTRY.csv")

TYPE_COLOR = {
    "硬约束": "#c62828", "策略": "#1565c0", "预算": "#ef6c00", "凭证": "#6a1b9a",
    "账本": "#2e7d32", "自证": "#455a64", "合约": "#1565c0", "审批": "#6a1b9a",
    "机制": "#00838f", "闭环": "#2e7d32",
}
LAYER_BAND = ["#37474f", "#455a64", "#546e7a", "#00695c", "#2e7d32", "#5d4037"]
FONT = "Microsoft YaHei, Noto Sans CJK SC, PingFang SC, sans-serif"

QUICK = [
    ("这一步改世界吗？", "不改 ⇒ 只走 L2（Movement 集合 + 物理前提 + 可逆性）。"),
    ("改世界呢？", "L1 谁授权/为什么（WriteGrant）→ L3 还有预算吗（WriteBudget）+ 执行期复验（CapabilityGate）→ L4 记哪种账（TEMP 必拆 / KEEP 不拆）。"),
    ("要拆吗？", "只拆自己放的、自上而下、材料回收（RestoreScopeTask；悬空桥面走侧拆兜底）。"),
    ("LLM 能自己决定吗？", "不能：只能提目标；拒绝走 Refused；未知模组能力**问用户**（权限契约）。"),
]


def load_rows():
    with open(REGISTRY, newline="", encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        sys.exit("注册表为空：%s" % REGISTRY)
    return rows


def group(rows):
    layers, order = {}, []
    for r in rows:
        if r["layer"] not in layers:
            layers[r["layer"]] = []
            order.append(r["layer"])
        layers[r["layer"]].append(r)
    return order, layers


def esc(s):
    return html.escape(s, quote=True)


def clip(s, n):
    return s if len(s) <= n else s[: n - 1] + "…"


# ---------------------------------------------------------------- OVERVIEW.md
def write_overview(rows, layers, stamp):
    lines = [
        "# 授权 / 审批框架总览（**自动生成**，勿手改）",
        "",
        "> **单一出处**：`docs/authz/AUTHZ_REGISTRY.csv`（Excel 可直接打开、批注；改它再跑 `bash tools/authz-map.sh`）",
        "> 生成时间：%s ｜ 闸门 %d 条" % (stamp, len(rows)),
        "",
        "## 四问速查（唯一需要背的东西）",
        "",
    ]
    for i, (q, a) in enumerate(QUICK, 1):
        lines.append("%d. **%s** %s" % (i, q, a))
    lines += ["", "## 六层一览", "", "| 层 | 闸门数 | 这一层在回答什么 |", "|---|---|---|"]
    meaning = {
        "L0 目标层": "LLM 能提什么目标、谁能越权直连、能力未知时问谁",
        "L1 请求层": "这次请求带什么策略/预算/凭证（**策略选择层**）",
        "L2 规划期": "这条边在物理与可逆性上**合不合法**（硬校验，不合格直接拒）",
        "L3 执行期": "**用当前世界事实复验** + 记下授权写入（事实可能已变）",
        "L4 收尾期": "记什么账、拆什么、材料回不回收（闭环）",
        "L5 验证层": "怎么**自证**夹具/探针的前提与零写入",
    }
    for layer in layers:
        lines.append("| %s | %d | %s |" % (layer, len(layers[layer]), meaning.get(layer, "")))
    lines += ["", "## 闸门明细", ""]
    for layer in layers:
        lines += ["### %s" % layer, "",
                  "| id | 类型 | 闸门 | 触发 | 拒绝码 | 代码位置 | 默认 | 谁能放开 |",
                  "|---|---|---|---|---|---|---|---|"]
        for r in layers[layer]:
            lines.append("| `%s` | %s | %s | %s | %s | `%s` | %s | %s |" % (
                r["id"], r["type"], r["gate"], r["trigger"], r["codes"], r["code_ref"],
                r["default"], r["unlock_by"]))
        lines.append("")
    lines += [
        "## 其它视图",
        "",
        "- 流程图：`docs/authz/flow.svg`（浏览器直接打开）",
        "- 可搜索页面：`docs/authz/index.html`（内嵌流程图 + 完整表格）",
        "- 文本版流转：`bash tools/authz-map.sh` 的 stdout 摘要",
        "",
    ]
    with open(os.path.join(OUT, "OVERVIEW.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


# ------------------------------------------------------------------- flow.svg
def write_svg(rows, layers, stamp):
    W = 1200
    top, row_h, head_h = 96, 30, 34
    blocks = [(layer, len(layers[layer])) for layer in layers]
    heights = [head_h + n * row_h + 12 for _, n in blocks]
    footer_h = 150
    H = top + sum(heights) + footer_h

    out = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" font-family="%s">'
           % (W, H, W, H, FONT),
           '<rect width="%d" height="%d" fill="#fafafa"/>' % (W, H),
           '<text x="24" y="42" font-size="24" font-weight="bold" fill="#212121">Alice 授权 / 审批框架（六层）</text>',
           '<text x="24" y="68" font-size="13" fill="#616161">单一出处 docs/authz/AUTHZ_REGISTRY.csv ｜ 生成 %s ｜ 闸门 %d 条 ｜ 硬约束=红 策略=蓝 预算=橙 凭证/审批=紫 账本/闭环=绿 自证=灰</text>'
           % (esc(stamp), len(rows))]

    y = top
    for idx, layer in enumerate(layers):
        h = head_h + len(layers[layer]) * row_h + 12
        band = LAYER_BAND[idx % len(LAYER_BAND)]
        out.append('<rect x="20" y="%d" width="150" height="%d" rx="8" fill="%s"/>' % (y, h, band))
        out.append('<text x="34" y="%d" font-size="15" font-weight="bold" fill="#ffffff">%s</text>'
                   % (y + 24, esc(layer)))
        out.append('<text x="34" y="%d" font-size="12" fill="#cfd8dc">%d 道闸门</text>'
                   % (y + 44, len(layers[layer])))
        out.append('<rect x="180" y="%d" width="996" height="%d" rx="8" fill="#ffffff" stroke="#e0e0e0"/>'
                   % (y, h))
        out.append('<line x1="95" y1="%d" x2="95" y2="%d" stroke="#90a4ae" stroke-width="2"/>'
                   % (y, y + h))
        if idx < len(layers) - 1:
            out.append('<path d="M95 %d l-6 -9 l12 0 z" fill="#90a4ae"/>' % (y + h + 10))
        for j, r in enumerate(layers[layer]):
            ry = y + head_h + j * row_h
            color = TYPE_COLOR.get(r["type"], "#455a64")
            out.append('<rect x="192" y="%d" width="4" height="18" rx="2" fill="%s"/>' % (ry - 14, color))
            out.append('<text x="206" y="%d" font-size="13.5" fill="#212121">%s <tspan fill="#9e9e9e">%s</tspan> %s</text>'
                       % (ry, esc(r["id"]), esc(r["type"]), esc(clip(r["gate"], 26))))
            out.append('<text x="620" y="%d" font-size="10.5" fill="#757575">%s</text>'
                       % (ry, esc(clip(r["codes"], 31))))
            out.append('<text x="975" y="%d" font-size="10" fill="#9e9e9e">%s</text>'
                       % (ry, esc(clip(r["code_ref"], 21))))
        y += h

    fy = H - footer_h + 6
    out.append('<rect x="20" y="%d" width="1156" height="%d" rx="8" fill="#fffde7" stroke="#fbc02d"/>'
               % (fy, footer_h - 20))
    out.append('<text x="36" y="%d" font-size="14" font-weight="bold" fill="#795548">四问速查</text>' % (fy + 24))
    for i, (q, a) in enumerate(QUICK):
        out.append('<text x="36" y="%d" font-size="12.5" fill="#4e342e">%d. %s %s</text>'
                   % (fy + 46 + i * 22, i + 1, esc(q), esc(clip(a, 96))))
    out.append("</svg>")
    with open(os.path.join(OUT, "flow.svg"), "w", encoding="utf-8") as f:
        f.write("\n".join(out))


# ------------------------------------------------------------------ index.html
def write_html(rows, layers, stamp):
    svg = open(os.path.join(OUT, "flow.svg"), encoding="utf-8").read()
    svg = svg.split("?>", 1)[-1]
    trs = []
    for layer in layers:
        for r in layers[layer]:
            trs.append(
                '<tr data-layer="%s" data-type="%s"><td class="id">%s</td><td>%s</td><td><span class="tag" style="background:%s">%s</span></td>'
                '<td>%s</td><td class="dim">%s</td><td class="dim">%s</td><td class="dim">%s</td><td class="dim">%s</td><td class="dim">%s</td></tr>'
                % (esc(r["layer"]), esc(r["type"]), esc(r["id"]), esc(r["layer"]),
                   TYPE_COLOR.get(r["type"], "#455a64"), esc(r["type"]), esc(r["gate"]),
                   esc(r["trigger"]), esc(r["codes"]), esc(r["code_ref"]),
                   esc(r["default"]), esc(r["unlock_by"])))
    quick = "".join("<li><b>%s</b> %s</li>" % (esc(q), esc(a)) for q, a in QUICK)
    doc = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<title>Alice 授权/审批框架</title><style>
body{{font-family:{font};margin:0;background:#f5f5f5;color:#212121}}
header{{padding:16px 24px;background:#263238;color:#fff}}
header h1{{margin:0 0 6px;font-size:20px}} header div{{font-size:12.5px;color:#b0bec5}}
main{{padding:16px 24px}}
section{{background:#fff;border-radius:8px;padding:14px 18px;margin-bottom:16px;box-shadow:0 1px 3px rgba(0,0,0,.12)}}
h2{{font-size:16px;margin:4px 0 10px}}
ul{{margin:6px 0 0 18px;padding:0}} li{{margin:4px 0;font-size:13.5px}}
input,select{{font-size:13px;padding:6px 8px;margin-right:10px;border:1px solid #cfd8dc;border-radius:6px}}
table{{border-collapse:collapse;width:100%;font-size:12.5px}}
th,td{{border-bottom:1px solid #eceff1;padding:6px 8px;text-align:left;vertical-align:top}}
th{{position:sticky;top:0;background:#eceff1;z-index:1}}
td.id{{font-family:ui-monospace,Consolas,monospace;white-space:nowrap}}
td.dim{{color:#616161}}
.tag{{color:#fff;border-radius:4px;padding:1px 6px;font-size:11.5px;white-space:nowrap}}
svg{{max-width:100%;height:auto}}
</style></head><body>
<header><h1>Alice 授权 / 审批框架</h1>
<div>单一出处 docs/authz/AUTHZ_REGISTRY.csv ｜ 生成 {stamp} ｜ 闸门 {n} 条</div></header>
<main>
<section><h2>四问速查</h2><ul>{quick}</ul></section>
<section><h2>流程图</h2>{svg}</section>
<section><h2>闸门明细（可筛选 / 搜索）</h2>
<div><input id="q" placeholder="搜索：闸门 / 拒绝码 / 代码位置" size="34">
<select id="lf"><option value="">全部层</option>{opts}</select>
<select id="tf"><option value="">全部类型</option>{topts}</select></div>
<p id="stat" style="font-size:12px;color:#757575"></p>
<table><thead><tr><th>id</th><th>层</th><th>类型</th><th>闸门</th><th>触发</th><th>拒绝码</th><th>代码位置</th><th>默认</th><th>谁能放开</th></tr></thead>
<tbody>{trs}</tbody></table></section>
</main><script>
const rows=[...document.querySelectorAll('tbody tr')];
function apply(){{const q=document.getElementById('q').value.toLowerCase();
const lf=document.getElementById('lf').value,tf=document.getElementById('tf').value;let n=0;
rows.forEach(r=>{{const okQ=!q||r.innerText.toLowerCase().includes(q);
const okL=!lf||r.dataset.layer===lf,okT=!tf||r.dataset.type===tf;
const show=okQ&&okL&&okT;r.style.display=show?'':'none';if(show)n++;}});
document.getElementById('stat').textContent='显示 '+n+' / '+rows.length+' 条';}}
['q','lf','tf'].forEach(id=>document.getElementById(id).addEventListener('input',apply));
apply();</script></body></html>""".format(
        font=FONT, stamp=esc(stamp), n=len(rows), quick=quick, svg=svg, trs="\n".join(trs),
        opts="".join('<option value="%s">%s</option>' % (esc(l), esc(l)) for l in layers),
        topts="".join('<option value="%s">%s</option>' % (esc(t), esc(t)) for t in sorted({r["type"] for r in rows})))
    with open(os.path.join(OUT, "index.html"), "w", encoding="utf-8") as f:
        f.write(doc)


CODE_RE = re.compile(r'"([A-Z][A-Z_]{4,})"')
# **显式的家族豁免表**（T0-a，2026-09-14）：`拒绝码 -> 注册表里已登记的等价条目文本`。
# 默认**空** ⇒ 每个拒绝码都必须**精确**出现在注册表里。
# 为什么要有这张表而不是"前缀包含"：原判据 `c.split("_")[0] in reg_text` 让**任意**以已出现词开头的新码
# 自动通过（实测 `BUDGET_TOTALLY_NEW_CODE` / `SEARCH_LIMIT_V9` / `PATH_NEW_THING` 全部通过）。
# 要豁免必须**逐条写在这里**，写明它等价于注册表里的哪一格 —— 可审计、写一个才放一个。
# 这张表此前是 `set()`（定义了但从未使用）；现在改成 dict 并真正生效。
FAMILY_OK: dict[str, str] = {}

DECLARED_RE = re.compile(r"(\d+)\s*码[：:]")
# 族名 ≥2 个大写字母/下划线，紧跟计数：`BREAK20` / `NO2`。
# ⚠️ 早先写成 `[A-Z][A-Z_]{2,}?`（要求 ≥3 个字母）⇒ **两字母族 `NO2` 解析不到**，
# 于是"已声明"的 `NO` 会被当成"新族"而误报（本轮实测踩到）。
FAMILY_RE = re.compile(r"([A-Z][A-Z_]+?)(\d+)")


def declared_code_families(rows):
    """从注册表 `codes` 列解析"**总数 + 每族计数**"（形如 `116 码：BREAK20 PLACE18 …`）。

    返回 `(总数, {族: 计数})`；找不到声明就返回 `(0, {})`（此时跳过族断言并由调用方提示）。
    """
    for row in rows:
        cell = row.get("codes", "") or ""
        found = DECLARED_RE.search(cell)
        if not found:
            continue
        return int(found.group(1)), {name: int(count) for name, count in FAMILY_RE.findall(cell)}
    return 0, {}


def render_families(actual, total):
    """把实际族分布渲染成可直接粘回注册表 `codes` 列的一行（机械生成，不靠人算）。

    **每族一律带计数**（`BREAK20 … NO2 BLOCKED1 …`）：早先写成"单码族 N 个：A B C"的简写，
    解析器读不到那些族名 ⇒ 会被当成"新族"。统一格式后生成与解析对称，不必人工维护两种写法。
    """
    ordered = sorted(actual.items(), key=lambda item: (-item[1], item[0]))
    return "%d 码：%s" % (total, " ".join("%s%d" % (f, n) for f, n in ordered))


def enum_values(src):
    """从枚举源码里取全部取值（按逗号切，兼容同一行多个取值）。"""
    body = src.split("{", 1)[-1].split(";", 1)[0]
    body = re.sub(r"//[^\n]*", "", body)
    # 只取"行首或逗号后、且后面紧跟 ( , ; 或行尾"的标识符 ⇒ 排除构造参数里的标识符（否则会过采）
    return [m.group(1) for m in re.finditer(r"(?:^\s*|,\s*)([A-Z][A-Z0-9_]*)\s*(?=[,(;]|\s*$)", body, re.M)]


def check(rows):
    """断言注册表与代码一致（防过期）。返回 (missing_codes, missing_types, missing_reasons)。

    ⚠️ 2026-09-14（T0-a 堵假绿）两处修正（三路审计 E-5 实证）：
    ① **递归**扫描 `pathing/core/**`。原 glob `pathing/core/*.java` **非递归** ⇒ `core/search/` 与
       `core/session/` 共 **21 个文件从不被扫描**，其中的拒绝码永远不会被断言；
    ② **去掉"家族前缀逃生"**（`c.split("_")[0] in reg_text`）⇒ 改为精确匹配，豁免只能写进 `FAMILY_OK`。
    """
    import glob
    pattern = os.path.join(ROOT, "src/main/java/com/dddgn/alice/pathing/core/**/*.java")
    files = glob.glob(pattern, recursive=True)
    blob = " ".join(open(f, encoding="utf-8").read() for f in files)
    code_codes = set(CODE_RE.findall(blob))
    reg_text = " ".join(r[c] for r in rows for c in r)
    # ① 拒绝码：注册表**按族登记**（`codes` 列里 `116 码：BREAK20 PLACE18 …`）——保留这个既定粒度，
    #    但把旧的"**前缀碰巧出现在任意列**就放行"（`c.split("_")[0] in reg_text`）换成**族集合 + 计数**断言：
    #      · 出现**新族** ⇒ 注册表从没登记过 ⇒ FAIL（旧判据下 `BUDGET_TOTALLY_NEW_CODE` /
    #        `SEARCH_LIMIT_V9` / `PATH_NEW_THING` 全部静默通过，三路审计 E-5b 实证）；
    #      · 已登记的族**计数对不上** ⇒ 有人加了码却没更新注册表 ⇒ FAIL（旧判据完全看不见这一种）。
    #    刻意**不做**逐码登记：那会新增 ~118 行散文，与本项目"不增散文"的约定冲突（见
    #    `docs/reviews/2026-09-14-项目完成度与优先级审查.md` §4）。
    declared_total, declared_families = declared_code_families(rows)
    # 排除**拼接前缀**：`CapabilityGate.java:73` 是 `"ZONE_" + reason.toUpperCase(...)`，
    # `CODE_RE` 会把 `"ZONE_"` 当成一个"码"（长度 5 恰好命中 `[A-Z][A-Z_]{4,}`）。
    # 判据：**以 `_` 结尾的字面量是片段、不是完整的码**（拼出来的真码在本轮仍不会被断言到，
    # 这一条如实记在 `docs/authz/OVERVIEW.md` 的已知边界里，不假装覆盖）。
    counted = sorted(c for c in code_codes if c not in FAMILY_OK and not c.endswith("_"))
    actual_families = {}
    for code in counted:
        family = code.split("_")[0]
        actual_families[family] = actual_families.get(family, 0) + 1
    code_problems = []
    for family in sorted(actual_families):
        if family not in declared_families:
            examples = ", ".join(sorted(c for c in counted if c.split("_")[0] == family)[:4])
            code_problems.append("新族 `%s`（%d 码：%s）未在注册表 codes 列登记"
                                 % (family, actual_families[family], examples))
    for family in sorted(declared_families):
        if family not in actual_families:
            code_problems.append("注册表登记了族 `%s`（声明 %d）但代码里一个都没有"
                                 % (family, declared_families[family]))
        elif declared_families[family] != actual_families[family]:
            code_problems.append("族 `%s` 计数不符：注册表声明 %d / 实际 %d"
                                 % (family, declared_families[family], actual_families[family]))
    if declared_total and declared_total != len(counted):
        code_problems.append("拒绝码**总数**不符：注册表声明 %d / 实际 %d" % (declared_total, len(counted)))
    # ② MovementType 枚举值全覆盖
    mt = open(os.path.join(ROOT, "src/main/java/com/dddgn/alice/pathing/core/MovementType.java"), encoding="utf-8").read()
    types = enum_values(mt)
    missing_types = [t for t in types if t not in reg_text]
    # ③ WriteReason 全覆盖
    wr = open(os.path.join(ROOT, "src/main/java/com/dddgn/alice/action/WriteReason.java"), encoding="utf-8").read()
    reasons = enum_values(wr)
    missing_reasons = [x for x in reasons if x not in reg_text]
    print("AUTHZ_CHECK 扫描文件=%d 拒绝码=%d（族=%d）MovementType=%d WriteReason=%d 显式豁免=%d"
          % (len(files), len(counted), len(actual_families), len(types), len(reasons), len(FAMILY_OK)))
    for label, items in (("拒绝码族/计数", code_problems),
                         ("未覆盖的 MovementType", missing_types),
                         ("未覆盖的 WriteReason", missing_reasons)):
        print("  %s: %s" % (label, "；".join(items) if items else "无 ✅"))
    if code_problems:
        print("  [FIX] 注册表 codes 列可机械改为：" + render_families(actual_families, len(counted)))
    ok = not (code_problems or missing_types or missing_reasons)
    print("AUTHZ_CHECK_RESULT", "PASS" if ok else "FAIL")
    return 0 if ok else 1


def main():
    rows = load_rows()
    if "--check" in sys.argv:
        sys.exit(check(rows))
    order, layers = group(rows)  # order=层顺序；layers=层名→闸门列表（dict 保持插入顺序）
    stamp = datetime.now().strftime("%Y-%m-%d %H:%M")
    write_overview(rows, layers, stamp)
    write_svg(rows, layers, stamp)
    write_html(rows, layers, stamp)
    print("AUTHZ_MAP OK  闸门=%d 层=%d  生成时间=%s" % (len(rows), len(layers), stamp))
    for layer in layers:
        print("  %-12s %2d 道" % (layer, len(layers[layer])))
    print("  -> docs/authz/OVERVIEW.md / flow.svg / index.html")


if __name__ == "__main__":
    main()
