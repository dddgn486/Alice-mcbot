# Alice DSH Runtime

This directory is local-only runtime state for the Alice-specific DSH service at `http://127.0.0.1:3083`.

- profile: `~/.dsh/profiles/alice-bus`
- sessions: `sessions/`
- agent-bus storage: `storages/`
- host logs: `logs/`
- desktop launcher: `C:\Users\dddgn\Desktop\工具\启动 Alice DSH 工作总线.cmd`
- desktop stop script: `C:\Users\dddgn\Desktop\工具\停止 Alice DSH 工作总线.cmd`
- `dsh-dafeiyu` is enabled with the same verified local Windows helper used by 3081 (`C:\Users\dddgn\AppData\Local\DSH\dsh-dafeiyu\dsh-dafeiyu-helper.exe`). Do not use its packaged WSL UNC path, which can stall startup. The separate 3081 Web profile is unchanged.

Do not commit runtime data. The project authorization source remains Git, `docs/HANDOVER.md`, `.alice-supervision/active-plan.md`, reviews, reports, and explicit user client evidence. `dsh-agent-bus` only coordinates the four Alice sessions.

The current active plan is `NEEDS_USER_DECISION`. Starting this host or creating the four role sessions does not authorize implementation, old-save loading, diagnostics, or automatic wake-up of the developer.
