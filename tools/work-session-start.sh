#!/usr/bin/env bash
# Read and validate the supervisor-approved work package before implementation.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: tools/work-session-start.sh [--allow-no-plan]

Reads .alice-supervision/active-plan.md and refuses to start implementation
unless it is marked APPROVED_FOR_IMPLEMENTATION. Use --allow-no-plan only for
user-authorized maintenance that cannot change project direction.
EOF
}

allow_no_plan=false
while (($#)); do
  case "$1" in
    --allow-no-plan) allow_no_plan=true ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

root="$(git rev-parse --show-toplevel)"
cd "$root"
plan="$root/.alice-supervision/active-plan.md"

if [[ ! -f "$plan" ]]; then
  if "$allow_no_plan"; then
    printf 'No active plan found; user-authorized exception recorded by caller.\n'
    exit 0
  fi
  printf 'Refusing to start: no active plan at %s\n' "$plan" >&2
  printf 'Ask the supervisor to create one from docs/supervision/ACTIVE_PLAN_TEMPLATE.md.\n' >&2
  exit 1
fi

if ! grep -Fxq -- '- Status: APPROVED_FOR_IMPLEMENTATION' "$plan"; then
  printf 'Refusing to start: active plan is not APPROVED_FOR_IMPLEMENTATION.\n' >&2
  grep -F -- '- Status:' "$plan" >&2 || true
  printf 'The supervisor must resolve review or user-acceptance gates first.\n' >&2
  exit 1
fi

if ! grep -Eq -- '^- Plan ID: `[^`]+`$' "$plan"; then
  printf 'Refusing to start: active plan has no valid Plan ID.\n' >&2
  exit 1
fi

research_state="$(sed -n 's/^- Deep research: //p' "$plan" | head -n 1)"
case "$research_state" in
  ""|"Not required"|"Completed") ;;
  "Required before implementation")
    printf 'Refusing to start: active plan requires deep research before implementation.\n' >&2
    exit 1
    ;;
  *)
    printf 'Refusing to start: active plan has invalid Deep research state: %s\n' "$research_state" >&2
    exit 1
    ;;
esac

if [[ "$research_state" == "Completed" ]]; then
  report_paths="$(sed -n 's/^- Required report path(s): `\([^`]*\)`$/\1/p' "$plan" | head -n 1)"
  if [[ -z "$report_paths" || "$report_paths" == "Not required" ]]; then
    printf 'Refusing to start: completed deep research has no report path.\n' >&2
    exit 1
  fi
  while IFS= read -r report; do
    [[ -z "$report" ]] && continue
    if [[ ! -f "$root/$report" ]]; then
      printf 'Refusing to start: required research report is missing: %s\n' "$report" >&2
      exit 1
    fi
  done < <(tr ',' '\n' <<<"$report_paths")
fi

printf 'Approved Alice work package:\n\n'
cat "$plan"
printf '\nStart gate passed. Before edits, read docs/HANDOVER.md and all design documents named by this plan.\n'
