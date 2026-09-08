#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT="${1:-${ROOT_DIR}/build/libs/alice-1.0.0-1.20.1.jar}"
WINDOWS_REPO="${2:-/mnt/d/JAVA_projects/alice}"
RUNTIME_MODS="${3:-}"

# 用法：sync-windows-artifact.sh [artifact.jar] [windows-repo] [runtime-mods-dir]
# 第三个参数显式给出实际客户端 mods 目录后，脚本才会同步运行工件。

if [[ ! -f "${ARTIFACT}" ]]; then
  echo "ERROR: artifact not found: ${ARTIFACT}" >&2
  exit 2
fi
if [[ ! -d "${WINDOWS_REPO}" ]]; then
  echo "ERROR: Windows repository directory not found: ${WINDOWS_REPO}" >&2
  exit 2
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
fi

echo "WINDOWS_ARTIFACT_SYNC PASS"
echo "artifact=${artifact_name}"
echo "source=${ARTIFACT}"
echo "source_size=${source_size}"
echo "source_sha256=${source_hash}"
echo "repository_target=${target}"
echo "repository_sha256=${target_hash}"
if [[ -n "${backup}" ]]; then
  echo "repository_backup=${backup}"
fi
if [[ -n "${runtime_target}" ]]; then
  echo "runtime_target=${runtime_target}"
  echo "runtime_sha256=${runtime_hash}"
fi
