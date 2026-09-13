#!/usr/bin/env bash
# 物品/方块模型资源自检（D-170）：抓"客户端静默降级"的两类资源缺陷 ——
#   ① 模型 JSON **空文件**（0 字节）或 JSON 语法坏 ⇒ 客户端 `JsonParseException: JSON data was null or empty`
#      + `Unable to load model: 'alice:xxx#inventory'` ⇒ 物品变成**缺失模型**（紫黑块/看不见）；
#   ② 模型引用的贴图**不存在** ⇒ 紫黑贴图（模型能加载，所以日志里只有 WARN，更容易漏）。
#
# 背景（2026-09-13 实测）：`k3_stop_check` / `menu_probe` / `transfer_check` 三个模型是 0 字节，
# `partial_search_check` 指向不存在的 `check_pathing_decision` —— 都是上一轮脚本写文件时留下的，
# 编译、打包、同步全部 PASS，**只有客户端日志能看出来**。所以本脚本必须在 build 前跑一遍。
#
# 用法：./tools/check-item-models.sh   （退出码 0 = PASS，1 = 有缺陷）
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS="$ROOT/src/main/resources/assets/alice/models"
TEXTURES="$ROOT/src/main/resources/assets/alice/textures"

fail=0
empty=0
bad_json=0
missing_tex=0
checked=0

while IFS= read -r model; do
    rel="${model#"$ROOT/src/main/resources/"}"          # assets/alice/models/item/x.json
    name="$(basename "$model" .json)"
    checked=$((checked + 1))

    size=$(wc -c < "$model")
    if [ "$size" -eq 0 ]; then
        echo "EMPTY     $rel （0 字节 ⇒ 客户端必然报 JSON data was null or empty）"
        empty=$((empty + 1)); fail=1
        continue
    fi
    if ! python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$model" >/dev/null 2>&1; then
        echo "BAD_JSON  $rel （JSON 语法错误）"
        bad_json=$((bad_json + 1)); fail=1
        continue
    fi

    # 逐个检查 "namespace:item/xxx" 形式的贴图引用（layer0/1/2…）
    while IFS= read -r ref; do
        ns="${ref%%:*}"
        path="${ref#*:}"                                  # item/xxx
        png="$ROOT/src/main/resources/assets/$ns/textures/$path.png"
        if [ ! -f "$png" ]; then
            echo "NO_TEXTURE $rel → $ref （缺 $ns/textures/$path.png）"
            missing_tex=$((missing_tex + 1)); fail=1
        fi
    done < <(grep -o '"[a-z_0-9]*:[a-z_0-9/]*"' "$model" | tr -d '"' | grep -v '^minecraft:item' | sort -u)
done < <(find "$MODELS" -name '*.json' | sort)

echo "CHECK_ITEM_MODELS checked=$checked empty=$empty bad_json=$bad_json missing_texture=$missing_tex"
if [ "$fail" -eq 0 ]; then
    echo "CHECK_ITEM_MODELS_RESULT PASS"
else
    echo "CHECK_ITEM_MODELS_RESULT FAIL"
fi
exit "$fail"
