# Emergency Manual Sync Record

- at: <ISO-8601 timestamp>
- operator: <session id or human>
- source_commit: <git commit the files were manually copied from>
- target_path: <where the files were copied to>
- sha256: <content SHA-256 of the copied set, when known>
- reason: <why the manual copy happened>
- recovery_plan: <how to reconcile this back to a git-tracked state>
