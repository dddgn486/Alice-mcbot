#!/usr/bin/env bash
# 生成「新设备即用」的包：桌面上的文件夹 + 同名 zip（zip 用于拷到新设备）。
# 用法：tools/make-cloud-tunnel-bundle.sh
set -uo pipefail
REPO="${ALICE_REPO:-/home/fb486/projects/alice}"
BACKUP="${ALICE_CLOUD_BACKUP_DIR:-/mnt/d/JAVA_projects/alice-backups}"
TS="$(date +%Y%m%d)"

WINHOME="$(cmd.exe /c 'echo %USERPROFILE%' 2>/dev/null | tr -d '\r')"
[ -n "$WINHOME" ] || { echo "✗ 拿不到 Windows 家目录（cmd.exe 不可达？）" >&2; exit 1; }
WINHOME_WSL="$(wslpath -u "$WINHOME")"
WIN_DESKTOP="$WINHOME_WSL/Desktop"
WIN_DESKTOP_WIN="$WINHOME\\Desktop"
DEST="$WIN_DESKTOP/alice-cloud-tunnel"
ZIP_WSL="$WIN_DESKTOP/alice-cloud-tunnel-$TS.zip"
ZIP_WIN="$WIN_DESKTOP_WIN\\alice-cloud-tunnel-$TS.zip"

mkdir -p "$DEST/本机WSL专用"
cp -f "$REPO/tools/codespace-tunnel.ps1" "$DEST/"
cp -f "$REPO/tools/codespace-tunnel.cmd" "$DEST/"
cp -f "$REPO/tools/cloud-rollback.sh"    "$DEST/本机WSL专用/"
cp -f "$REPO/tools/client-info-watch.ps1" "$DEST/"
cp -f "$REPO/tools/client-info-watch.cmd" "$DEST/"
# client-agent 整个目录一起拷（2026-09-24 教训：逐个 cp 会漏文件 —— 漏了 install.ps1 导致新设备上 -Install 直接失败）
mkdir -p "$DEST/presets"
cp -rf "$REPO/tools/client-agent/presets/." "$DEST/presets/"
cp -f "$REPO/tools/client-agent/"*.cmd "$DEST/" 2>/dev/null || true
cp -f "$REPO/tools/client-agent/"*.ps1 "$DEST/"
# 说明文件转成 CRLF + UTF-8 BOM（记事本 / PowerShell 5.1 都友好）
python3 - "$REPO/tools/cloud-tunnel-README.txt" "$DEST/怎么用.txt" <<'PY'
import sys, pathlib
src, dst = sys.argv[1], sys.argv[2]
t = pathlib.Path(src).read_text(encoding="utf-8").replace("\r\n", "\n").replace("\n", "\r\n")
pathlib.Path(dst).write_bytes(("\ufeff" + t).encode("utf-8"))
PY
# .cmd 必须是 CRLF（源文件已是 CRLF；这里再确认一次，避免被编辑器/工具改坏）
python3 - "$DEST/codespace-tunnel.cmd" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1]); b = p.read_bytes()
if b"\r\n" not in b: p.write_bytes(b.replace(b"\n", b"\r\n"))
PY

# zip 用 Windows 自带的 Compress-Archive（解压到新设备无坑）
powershell.exe -NoProfile -Command "Compress-Archive -Force -Path '$WIN_DESKTOP_WIN\\alice-cloud-tunnel\\*' -DestinationPath '$ZIP_WIN'" >/dev/null 2>&1
[ -f "$ZIP_WSL" ] || { echo "✗ zip 没生成" >&2; exit 1; }
cp -f "$ZIP_WSL" "$BACKUP/" 2>/dev/null || true

echo "→ 桌面文件夹：$WIN_DESKTOP_WIN\\alice-cloud-tunnel\\"
find "$DEST" -type f | sed "s|^$DEST/|    |" | sort
echo "→ zip（拷到新设备用）：$ZIP_WIN"
printf '   大小 = %s   sha256 = %s\n' "$(du -h "$ZIP_WSL" | cut -f1)" "$(sha256sum "$ZIP_WSL" | cut -c1-16)"
[ -f "$BACKUP/$(basename "$ZIP_WSL")" ] && echo "→ 已备份到 $BACKUP/$(basename "$ZIP_WSL")"
