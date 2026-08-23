#!/usr/bin/env bash
# Generate a local, immutable review packet after a work-session handover update.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: tools/session-complete.sh [--from-hook] [--allow-no-handover]

Creates .alice-supervision/pending/<commit>.md for the current HEAD.
The packet is intentionally local and ignored by Git. A configured notifier receives
its absolute path as the first argument after a successful packet write.

Environment:
  ALICE_SUPERVISOR_NOTIFY_CMD  Command that accepts the packet path as $1.
EOF
}

from_hook=false
allow_no_handover=false
while (($#)); do
  case "$1" in
    --from-hook) from_hook=true ;;
    --allow-no-handover) allow_no_handover=true ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

root="$(git rev-parse --show-toplevel)"
cd "$root"
commit="$(git rev-parse HEAD)"
short_commit="$(git rev-parse --short HEAD)"
parent="$(git rev-parse HEAD^)"
changed_files="$(git diff-tree --no-commit-id --name-only -r HEAD)"

if ! grep -qx 'docs/HANDOVER.md' <<<"$changed_files" && ! "$allow_no_handover"; then
  if "$from_hook"; then
    exit 0
  fi
  printf 'Refusing to close the session: HEAD does not update docs/HANDOVER.md.\n' >&2
  printf 'Update the handover first, or use --allow-no-handover for documentation-only exceptions.\n' >&2
  exit 1
fi

supervision_dir="$root/.alice-supervision"
plan="$supervision_dir/active-plan.md"
queue_dir="$supervision_dir/pending"
mkdir -p "$queue_dir"
packet="$queue_dir/${short_commit}.md"
if [[ -e "$packet" ]]; then
  printf 'Review packet already exists: %s\n' "$packet"
  exit 0
fi

handover_changed=false
if grep -qx 'docs/HANDOVER.md' <<<"$changed_files"; then
  handover_changed=true
fi

plan_id="none"
plan_status="missing"
if [[ -f "$plan" ]]; then
  plan_id="$(sed -n 's/^- Plan ID: `\([^`]*\)`$/\1/p' "$plan" | head -n 1)"
  plan_status="$(sed -n 's/^- Status: //p' "$plan" | head -n 1)"
  [[ -n "$plan_id" ]] || plan_id="invalid"
  [[ -n "$plan_status" ]] || plan_status="invalid"
fi

{
  printf '# Alice work-session review packet\n\n'
  printf -- '- Commit: `%s`\n' "$commit"
  printf -- '- Subject: %s\n' "$(git log -1 --format=%s HEAD)"
  printf -- '- Authored: %s\n' "$(git log -1 --format=%cI HEAD)"
  printf -- '- Handover updated in this commit: `%s`\n' "$handover_changed"
  printf -- '- Active plan ID: `%s`\n' "$plan_id"
  printf -- '- Active plan status at handoff: `%s`\n' "$plan_status"
  printf -- '- Push status: verify separately after `git push origin master`\n\n'
  printf '## Changed files\n\n```text\n%s\n```\n\n' "$changed_files"
  printf '## Commit summary\n\n```text\n'
  git diff --stat "$parent" HEAD
  printf '```\n\n## Handover delta\n\n'
  if "$handover_changed"; then
    printf '```diff\n'
    git diff --unified=3 "$parent" HEAD -- docs/HANDOVER.md
    printf '```\n'
  else
    printf 'No handover change was included; this closure was explicitly overridden.\n'
  fi
  printf '\n## Active Plan Snapshot\n\n'
  if [[ -f "$plan" ]]; then
    printf '```markdown\n'
    cat "$plan"
    printf '```\n'
  else
    printf 'No active plan existed when this work session closed.\n'
  fi
  printf '\n## Supervisor checklist\n\n'
  printf -- '- Does this change preserve the architecture: goal-level decision, deterministic execution, server authority, semantic GUI interfaces?\n'
  printf -- '- Does it preserve the separate boundaries among `HARD_PATH`, experimental `SOFT_SURFACE`, forced road construction, survival monitoring, and future tunnel planning?\n'
  printf -- '- Does it contradict a numbered decision in `docs/HANDOVER.md` or the frozen plan in `docs/PATHING_REFACTOR.md`?\n'
  printf -- '- Are verification evidence, client-test requirements, known limitations, and the next safe step recorded accurately?\n'
  printf -- '- Was `./gradlew compileJava` run and was the committed revision pushed to `origin/master`?\n'
} > "$packet"

printf 'Created supervisor review packet: %s\n' "$packet"
if [[ -n "${ALICE_SUPERVISOR_NOTIFY_CMD:-}" ]]; then
  bash -c "$ALICE_SUPERVISOR_NOTIFY_CMD" -- "$packet"
  printf 'Supervisor notifier completed.\n'
else
  printf 'No notifier configured; the packet remains queued for the supervisor session.\n'
fi
