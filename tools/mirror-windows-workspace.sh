#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="${ROOT_DIR}/"
TARGET="${1:-/mnt/d/JAVA_projects/alice/}"
BACKUP_ROOT="${2:-/mnt/d/JAVA_projects/alice-backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP="${BACKUP_ROOT}/alice-${STAMP}"
# **轮转**：只保留最近 N 份（默认 2），可用 ALICE_BACKUP_KEEP 覆盖。
# 2026-09-12 教训：本脚本原先"每跑一次建一份完整备份且从不清理" ⇒ 330 份 × 482MB ≈ 159GB，
# 直接把 D 盘写满（剩 4.8MB），镜像与产物同步全部失败。备份是防"镜像写坏"的，不是历史归档。
BACKUP_KEEP="${ALICE_BACKUP_KEEP:-2}"
# **默认不备份、不做全量校验**（2026-09-12 用户实测反馈"怎么这么慢"）：
#  - 备份会把整份目标再复制一遍（工作量翻倍）；
#  - 末尾的 `rsync -an` 校验又是一次全量扫描；
#  - 跨 drvfs 的几万个小文件本来就慢，三趟全量 = 慢上加慢。
# 需要时显式开启：`ALICE_MIRROR_BACKUP=1 ./tools/mirror-windows-workspace.sh`（配合 ALICE_BACKUP_KEEP）。
MIRROR_BACKUP="${ALICE_MIRROR_BACKUP:-0}"
MIRROR_VERIFY="${ALICE_MIRROR_VERIFY:-0}"

if [[ ! -d "${TARGET}" ]]; then
  echo "ERROR: target workspace not found: ${TARGET}" >&2
  exit 2
fi
if [[ "${MIRROR_BACKUP}" == "1" ]]; then
  mkdir -p "${BACKUP_ROOT}"
fi

# Windows is a source mirror only: never copy WSL Git metadata or local runtime state.
# **备份同样要套排除规则**：否则 `build/` 被一起复制，单份 482MB（源代码只有几十 MB）。
if [[ "${MIRROR_BACKUP}" == "1" ]]; then
rsync -a --delete --human-readable --info=stats2 \
  --exclude='.git/' \
  --exclude='.gradle/' \
  --exclude='.dsh-runtime/' \
  --exclude='build/' \
  --exclude='run/' \
  --exclude='videos/' \
  "${TARGET}" "${BACKUP}/"

# 轮转：保留最近 BACKUP_KEEP 份，其余删除（并在输出里说明删了什么）
pruned=0
if [[ "${BACKUP_KEEP}" =~ ^[0-9]+$ ]] && [[ "${BACKUP_KEEP}" -ge 1 ]]; then
  while IFS= read -r old_backup; do
    [[ -z "${old_backup}" ]] && continue
    rm -rf -- "${old_backup}"
    pruned=$((pruned + 1))
    echo "pruned_backup=$(basename "${old_backup}")"
  done < <(ls -1dt "${BACKUP_ROOT}"/alice-* 2>/dev/null | tail -n +$((BACKUP_KEEP + 1)))
fi
else
  pruned=0
  echo "backup=skipped（默认不备份；需要时 ALICE_MIRROR_BACKUP=1）"
fi

rsync -a --delete --human-readable --info=stats2 \
  --exclude='.git/' \
  --exclude='.gradle/' \
  --exclude='.dsh-runtime/' \
  --exclude='build/' \
  --exclude='run/' \
  --exclude='videos/' \
  "${SOURCE}" "${TARGET}"

if [[ -e "${TARGET}/.git" ]]; then
  echo "ERROR: mirror target must not contain .git: ${TARGET}/.git" >&2
  exit 3
fi

remaining="skipped"
if [[ "${MIRROR_VERIFY}" == "1" ]]; then
  remaining="$(rsync -an --delete \
    --exclude='.git/' --exclude='.gradle/' --exclude='.dsh-runtime/' \
    --exclude='build/' --exclude='run/' --exclude='videos/' \
    "${SOURCE}" "${TARGET}" | wc -l)"
  if [[ "${remaining}" != "0" ]]; then
    echo "ERROR: mirror has remaining differences: ${remaining}" >&2
    exit 4
  fi
fi

echo "WINDOWS_WORKSPACE_MIRROR PASS"
echo "source=${ROOT_DIR}"
echo "target=${TARGET}"
echo "backup=${BACKUP}"
echo "backup_keep=${BACKUP_KEEP}"
echo "backups_pruned=${pruned}"
echo "remaining_differences=${remaining}（默认跳过校验；ALICE_MIRROR_VERIFY=1 开启）"
echo "windows_git=absent"
