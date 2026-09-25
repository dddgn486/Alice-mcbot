#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT="${1:-${ROOT_DIR}/build/libs/alice-1.0.0-1.20.1.jar}"
WINDOWS_REPO="${2:-/mnt/d/JAVA_projects/alice}"
RUNTIME_MODS="${3:-}"

# 用法：sync-windows-artifact.sh [artifact.jar] [windows-repo] [runtime-mods-dir] [client-world-dir]
# 第三个参数显式给出实际客户端 mods 目录后，脚本才会同步运行工件。
# 第四个参数（或 `ALICE_CLIENT_WORLD`）给出客户端存档目录 ⇒ 顺带刷新场景数据包
# `<world>/datapacks/alice_test`（**D-251 实测的坑**：客户端那份是手工拷贝，没人刷新 ⇒ 新场景
# 的 `/function` 静默失败、场景拿没有地形的世界去规划，看着像 mod 回归）。

if [[ ! -f "${ARTIFACT}" ]]; then
  echo "ERROR: artifact not found: ${ARTIFACT}" >&2
  exit 2
fi
if [[ ! -d "${WINDOWS_REPO}" ]]; then
  echo "ERROR: Windows repository directory not found: ${WINDOWS_REPO}" >&2
  exit 2
fi

# ⭐ **陈旧工件护栏**（`D-440` 实测事故，2026-09-25）：`./gradlew build` **失败**时
# `build/libs/*.jar` **不会更新**（Gradle 直接停在上一个成功的工件上），而本脚本照样把它拷进客户端
# ⇒ 你测的是**上一版**代码，症状是"改了行为却没生效"（比崩溃更难查）。
# 判据：`src/main` 下有**比工件更新**的源文件 ⇒ 拒绝同步（除非显式跳过）。
# 成本 ≈ 一次 `find`；挂在**已有入口**上，不新增命令。
if [[ "${ALICE_ALLOW_STALE_ARTIFACT:-0}" != "1" ]]; then
  newer_source="$(find "${ROOT_DIR}/src/main" -type f -newer "${ARTIFACT}" -print -quit 2>/dev/null || true)"
  if [[ -n "${newer_source}" ]]; then
    echo "ERROR: 工件比源码旧 ⇒ **拒绝同步**（先跑通 ./gradlew build --offline 再同步）" >&2
    echo "  比工件更新的文件（其一）：${newer_source}" >&2
    echo "  确实要同步旧工件：ALICE_ALLOW_STALE_ARTIFACT=1 $0 …" >&2
    exit 4
  fi
fi

artifact_name="$(basename "${ARTIFACT}")"
target_dir="${WINDOWS_REPO}/build/libs"
target="${target_dir}/${artifact_name}"
mkdir -p "${target_dir}"

source_hash="$(sha256sum "${ARTIFACT}" | awk '{print $1}')"
source_size="$(stat -c '%s' "${ARTIFACT}")"
backup=""
if [[ -f "${target}" ]]; then
  backup="${target}.bak.$(date +%Y%m%d-%H%M%S)"
  cp -p "${target}" "${backup}"
fi

cp -f "${ARTIFACT}" "${target}"
target_hash="$(sha256sum "${target}" | awk '{print $1}')"
if [[ "${source_hash}" != "${target_hash}" ]]; then
  echo "ERROR: repository artifact hash mismatch" >&2
  echo "source=${source_hash} target=${target_hash}" >&2
  exit 3
fi

runtime_target=""
runtime_hash=""
if [[ -n "${RUNTIME_MODS}" ]]; then
  if [[ ! -d "${RUNTIME_MODS}" ]]; then
    echo "ERROR: runtime mods directory not found: ${RUNTIME_MODS}" >&2
    exit 2
  fi
  runtime_target="${RUNTIME_MODS}/${artifact_name}"
  if [[ -f "${runtime_target}" ]]; then
    cp -p "${runtime_target}" "${runtime_target}.bak.$(date +%Y%m%d-%H%M%S)"
  fi
  cp -f "${ARTIFACT}" "${runtime_target}"
  runtime_hash="$(sha256sum "${runtime_target}" | awk '{print $1}')"
  if [[ "${source_hash}" != "${runtime_hash}" ]]; then
    echo "ERROR: runtime artifact hash mismatch" >&2
    echo "source=${source_hash} runtime=${runtime_hash}" >&2
    exit 3
  fi
  # **运行时备份必须轮转**（2026-09-13 实测教训）：这里原先每次同步都留一份
  # `<jar>.bak.<时间戳>` 且从不清理 ⇒ 客户端 mods/ 目录累积 **271 份备份、241 MB**
  # （目录总 287 MB 里 84% 是备份）——与"D 盘被 330 份镜像备份写满"是**同一类**问题，
  # 只是发生地点不同。只保留最新 ${ALICE_BACKUP_KEEP:-2} 份。
  keep="${ALICE_BACKUP_KEEP:-2}"
  mapfile -t stale < <(ls -t "${runtime_target}".bak.* 2>/dev/null | tail -n +$((keep + 1)))
  if (( ${#stale[@]} > 0 )); then
    rm -f "${stale[@]}"
    echo "runtime_backups_pruned=${#stale[@]} (keep=${keep})"
  fi
fi

# ==================== 场景数据包（客户端存档里那一份） ====================
# D-251 实测教训：无头电池每轮 `cp -r tools/test-scenes/alice_test`，而**客户端存档里的那份是手工拷贝**
# ⇒ 新场景函数（`water_course_terrain` / `deep_pond_course_terrain`）缺失，`/function` 又因夹具
# `withSuppressedOutput()` 静默失败 ⇒ 场景拿"没有地形"的世界去规划，看起来像 mod 回归。所以挂到这里一起刷。
CLIENT_WORLD="${4:-${ALICE_CLIENT_WORLD:-}}"
datapack_src="${ROOT_DIR}/tools/test-scenes/alice_test"
if [[ -z "${CLIENT_WORLD}" && -n "${RUNTIME_MODS}" ]]; then
  client_root="$(dirname "${RUNTIME_MODS}")"
  mapfile -t candidates < <(ls -d "${client_root}"/saves/*/ 2>/dev/null || true)
  found=()
  for w in "${candidates[@]:-}"; do
    [[ -d "${w}/datapacks/alice_test" ]] && found+=("${w%/}")
  done
  if (( ${#found[@]} == 1 )); then
    CLIENT_WORLD="${found[0]}"
  elif (( ${#found[@]} > 1 )); then
    echo "client_datapack=skipped（自动探测到 ${#found[@]} 个存档都带 alice_test，请显式给第 4 个参数）"
  fi
fi
if [[ -n "${CLIENT_WORLD}" && -d "${CLIENT_WORLD}" && -d "${datapack_src}" ]]; then
  dp_target="${CLIENT_WORLD}/datapacks/alice_test"
  if [[ -d "${dp_target}" ]]; then
    # ⚠️ `D-412`：备份**必须放在 `datapacks/` 之外**。放在里面的话它本身就是一个
    # **提供同名命名空间（`alice_test`）的活数据包**，且加载顺序排在 `alice_test` 之后
    # ⇒ **它会盖住刚复制进去的那份**，跑的还是旧场景，而且**没有任何报错**
    #（2026-09-20 起母本里积了 2 份这样的包，直到 2026-09-23 才暴露）。
    dp_backup_dir="${CLIENT_WORLD}/.alice-datapack-backups"
    mkdir -p "${dp_backup_dir}"
    dp_backup="${dp_backup_dir}/alice_test.$(date +%Y%m%d-%H%M%S)"
    cp -r "${dp_target}" "${dp_backup}"
    keep_dp="${ALICE_BACKUP_KEEP:-2}"
    mapfile -t stale_dp < <(ls -dt "${dp_backup_dir}"/alice_test.* 2>/dev/null | tail -n +$((keep_dp + 1)))
    (( ${#stale_dp[@]} > 0 )) && rm -rf "${stale_dp[@]}"
    # 顺手清掉历史遗留的**同名活数据包**（它们会静默盖住新场景）
    mapfile -t legacy_dp < <(ls -d "${CLIENT_WORLD}/datapacks/alice_test".bak.* 2>/dev/null)
    if (( ${#legacy_dp[@]} > 0 )); then
      rm -rf "${legacy_dp[@]}"
      echo "removed_legacy_datapacks=${#legacy_dp[@]}（同命名空间的活备份 ⇒ 会盖住新场景，D-412）"
    fi
  fi
  rm -rf "${dp_target}"
  cp -r "${datapack_src}" "${dp_target}"
  src_fns="$(find "${datapack_src}/data" -name '*.mcfunction' | wc -l)"
  dst_fns="$(find "${dp_target}/data" -name '*.mcfunction' | wc -l)"
  echo "client_datapack=${dp_target}"
  echo "client_datapack_functions=${dst_fns} (repo=${src_fns})"
  if [[ "${src_fns}" != "${dst_fns}" ]]; then
    echo "ERROR: datapack function count mismatch after copy" >&2
    exit 3
  fi
  echo "client_datapack_note=请在游戏内执行 /reload（或重进存档）后再跑测试物品"
else
  echo "client_datapack=skipped（未给客户端存档目录；传第 4 个参数或设 ALICE_CLIENT_WORLD）"
fi

echo "WINDOWS_ARTIFACT_SYNC PASS"
echo "artifact=${artifact_name}"
echo "source=${ARTIFACT}"
echo "source_size=${source_size}"
echo "source_sha256=${source_hash}"
# **内容摘要**（2026-09-17 补）：Gradle 打的 jar **不可字节复现**（同一源码两次构建 sha256 不同）
# ⇒ 文件 sha256 只能证明"当时同步的是同一个文件"；要回答"客户端是不是现在这版代码"必须用内容摘要。
if [ -x "$(dirname "$0")/jar-content-hash.sh" ]; then
    src_content="$("$(dirname "$0")/jar-content-hash.sh" "${ARTIFACT}" 2>/dev/null | grep -o 'JAR_CONTENT_SHA256=.*' | cut -d= -f2)"
    [ -n "$src_content" ] && echo "content_sha256=${src_content}"
fi
echo "repository_target=${target}"
echo "repository_sha256=${target_hash}"
if [[ -n "${backup}" ]]; then
  echo "repository_backup=${backup}"
fi
if [[ -n "${runtime_target}" ]]; then
  echo "runtime_target=${runtime_target}"
  echo "runtime_sha256=${runtime_hash}"
fi
