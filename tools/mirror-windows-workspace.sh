#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="${ROOT_DIR}/"
TARGET="${1:-/mnt/d/JAVA_projects/alice/}"
BACKUP_ROOT="${2:-/mnt/d/JAVA_projects/alice-backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP="${BACKUP_ROOT}/alice-${STAMP}"

if [[ ! -d "${TARGET}" ]]; then
  echo "ERROR: target workspace not found: ${TARGET}" >&2
  exit 2
fi
mkdir -p "${BACKUP_ROOT}"

# Windows is a source mirror only: never copy WSL Git metadata or local runtime state.
rsync -a --human-readable --info=stats2 \
  "${TARGET}" "${BACKUP}/"

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

remaining="$(rsync -an --delete \
  --exclude='.git/' --exclude='.gradle/' --exclude='.dsh-runtime/' \
  --exclude='build/' --exclude='run/' --exclude='videos/' \
  "${SOURCE}" "${TARGET}" | wc -l)"
if [[ "${remaining}" != "0" ]]; then
  echo "ERROR: mirror has remaining differences: ${remaining}" >&2
  exit 4
fi

echo "WINDOWS_WORKSPACE_MIRROR PASS"
echo "source=${ROOT_DIR}"
echo "target=${TARGET}"
echo "backup=${BACKUP}"
echo "remaining_differences=${remaining}"
echo "windows_git=absent"
