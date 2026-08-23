#!/usr/bin/env bash
# Install a local post-commit hook that queues a review packet for handover commits.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
hooks_dir="$(git -C "$root" rev-parse --git-path hooks)"
hook="$hooks_dir/post-commit"
mkdir -p "$hooks_dir"

marker='# Alice supervision hook'
if [[ -e "$hook" ]] && grep -Fq "$marker" "$hook"; then
  chmod +x "$hook" "$root/tools/session-complete.sh" "$root/tools/work-session-start.sh" "$root/tools/install-supervision-hook.sh"
  printf 'Alice supervision post-commit hook already installed: %s\n' "$hook"
  exit 0
fi

if [[ ! -e "$hook" ]]; then
  printf '#!/usr/bin/env bash\n' > "$hook"
fi

printf '\n%s\n"%s/tools/session-complete.sh" --from-hook\n' "$marker" "$root" >> "$hook"
chmod +x "$hook" "$root/tools/session-complete.sh" "$root/tools/work-session-start.sh" "$root/tools/install-supervision-hook.sh"
printf 'Installed Alice supervision post-commit hook: %s\n' "$hook"
