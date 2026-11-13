# Ticket runner

A local-model agent (Claude Code on the LAN inference box, `claude-local`) takes
one ticket from **Todo** to **Done** on the
[Scarcity board](https://github.com/orgs/scarcity-game/projects/1). A human or
the repo agent then accepts or rejects the pull request. Rules and reasons:
root `AGENTS.md` ("The board") and `docs/decisions.md`, 2026-11-13.

## Columns
| column | meaning | who moves a ticket here |
|---|---|---|
| Backlog | not ready for an agent | anyone |
| Todo | ready: written for an agent, has the `agent: local` label | the repo agent or a human |
| Doing | an agent is working on it | the runner |
| Done | pull request open, module tests passed locally | the runner |
| Accepted | reviewed and merged | `accept` |
| Blocked | failed or sent back twice; needs a module lead or a human | the runner, `reject` |

## A ticket
An issue with the `agent: local` label whose body has:
- a line `Module: <path>` (a module path from `modules.yaml`, e.g. `Module: clients/web`);
- acceptance criteria as a checklist;
- the files and contract sections to read first.

One ticket changes one module. The agent may read the whole repo.

## Commands
Run from the main checkout.

```bash
tools/kanban/kanban.py status
tools/kanban/kanban.py add 71 77 --to Todo
tools/kanban/kanban.py run 71
tools/kanban/kanban.py loop --limit 5
tools/kanban/kanban.py review <pr number>            # a frontier model reviews, posts its verdict
tools/kanban/kanban.py review <pr number> --apply    # ...and accepts or rejects on it
tools/kanban/kanban.py accept <pr number>
tools/kanban/kanban.py reject <pr number> "what is wrong, concretely"
```

New tickets: open an issue with the **Ticket for the local model** template
(`.github/ISSUE_TEMPLATE/ticket.md`), then `add <n> --to Todo`.

## Review
`review` checks out the pull request's branch in the ticket's worktree and
runs `claude -p` on the subscription (`KANBAN_REVIEW_MODEL`, default `sonnet`;
never the local endpoint), read-only, with the ticket and the diff. It judges
the acceptance items, the contracts, the tests and the scope, and ends with
`VERDICT: ACCEPT` or `VERDICT: REJECT`. The review is posted on the pull
request. With `--apply` an ACCEPT runs `accept` (which waits for CI and
merges, so it **deploys**) and a REJECT runs `reject` with the review as the
reason. Use `--apply` only when the human has said merges may go out without
them; otherwise read the verdict and decide.

## What `run` does
1. Moves the ticket to Doing and makes a worktree `.claude/worktrees/ticket-<n>`
   on branch `ticket/<n>` from `origin/main` (or reuses the branch of an
   earlier attempt).
2. Starts `claude-local -p` there with the ticket, any earlier review, and a
   fixed tool allowlist: file tools, `mvn`, `npm test`, `npm run`, `npx vitest`,
   `npx tsc`, read-only git. It cannot push, commit or install packages.
3. **The gate**, run by the script, not the model: something changed; nothing
   changed outside the module; the module's `test` command from `modules.yaml`
   passes. On a failure the agent gets the output and up to two more rounds.
4. On a pass: commits, pushes the branch, opens a pull request, comments on
   the ticket and moves it to Done. CI runs on the pull request.
5. On a failure: commits the work in progress locally, comments on the ticket
   with the failing output and moves it back to Todo, or to Blocked on the
   second failure or rejection.

If the runner itself crashes, the ticket goes back to Todo. Every agent
turn's raw output is appended to `.claude/worktrees/ticket-<n>.log`.

`accept` waits for the pull request's checks and merges only when they are
green. A merge to `main` deploys.

## Settings
`KANBAN_AGENT_TIMEOUT` (seconds per agent turn, default 2700),
`KANBAN_TEST_TIMEOUT` (default 1500), `KANBAN_FIX_ROUNDS` (default 2),
`KANBAN_CI_TIMEOUT` (seconds `accept` waits for checks, default 2400),
`KANBAN_REVIEW_MODEL` (default `sonnet`).
