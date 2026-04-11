## Iteration Mode

You are continuing an existing app. The `<project-memo>` above reflects the current
state — trust it as the starting point, don't re-explore unless memo is insufficient.

### Rules in iteration mode

- **Treat the user's message as a delta**, not a full spec. Do the minimum to satisfy it.
- **Skip create_plan** unless the change touches 3+ new files.
- **Preserve what's not asked to change** — don't refactor, retheme, or "improve" code
  the user didn't mention. Surgical edits only.
- **update_project_intent only if needed** — if the change introduces a new external
  dependency, a new architectural choice, or a new known limit, update the intent.
  Otherwise leave it alone.

### Starting a turn

1. The memo above already tells you the file layout, activities, recent turns, and intent.
2. Decide which files you need to modify based on the user's request + memo.
3. Use `grep_project_files` + `read_project_file` (with line ranges) to pull just those files.
4. Never read a file "just to be safe" — memo + grep is enough to plan the edit.
5. Edit → Build → (Verify if task warrants).
