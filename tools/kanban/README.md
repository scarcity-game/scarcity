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
Run from the checkout the runner owns (on the box: `~/sandbox/scarcity`). `run`
and `loop` reset and clean it; `review` and `log` are safe anywhere.

```bash
tools/kanban/kanban.py status
tools/kanban/kanban.py add 71 77 --to Todo
tools/kanban/kanban.py run 71
tools/kanban/kanban.py loop --limit 5
tools/kanban/kanban.py review <pr number>            # a frontier model reviews, posts its verdict
tools/kanban/kanban.py review <pr number> --apply    # ...and accepts or rejects on it
tools/kanban/kanban.py accept <pr number>
tools/kanban/kanban.py reject <pr number> "what is wrong, concretely"
tools/kanban/kanban.py log 71                       # the agent runs on a ticket, one line per step
```

New tickets: open an issue with the **Ticket for the local model** template
(`.github/ISSUE_TEMPLATE/ticket.md`), then `add <n> --to Todo`.

## Review
`review` checks out the pull request's branch in a temporary worktree and
runs `claude -p` on the subscription (`KANBAN_REVIEW_MODEL`, default `sonnet`;
never the local endpoint), read-only, with the ticket and the diff. It judges
the acceptance items, the contracts, the tests and the scope, and ends with
`VERDICT: ACCEPT` or `VERDICT: REJECT`. The review is posted on the pull
request. With `--apply` an ACCEPT runs `accept` (which waits for CI and
merges, so it **deploys**) and a REJECT runs `reject` with the review as the
reason. Use `--apply` only when the human has said merges may go out without
them; otherwise read the verdict and decide.

## What `run` does
The runner works in the checkout it is started from, which it treats as its
own: it resets and cleans it (`git reset --hard`, `git clean -fd`; ignored
files such as `node_modules/` and the logs stay). It refuses to start when
that checkout has changes on a branch that is not a `ticket/*` branch.

1. Moves the ticket to Doing, cleans the checkout and switches to branch
   `ticket/<n>`: a new one from `origin/main`, or the branch of an earlier
   attempt. For a node module it runs a fresh `npm ci`.
2. Starts `claude-local -p` at the repo root with the ticket, any earlier
   review, and the module's directory and commands. Its tools are only Read,
   Grep, Glob, Edit, Write and Bash, with no MCP servers. Permission prompts
   are off: it may change anything, make temp files, install and add
   dependencies. Denied: `git commit/push/checkout/switch/reset/rebase/merge/
   stash/worktree` and `gh`.
3. **The gate**, run by the script, not the model: it commits the changes
   under the module, throws away everything else, checks that the branch
   has something new and runs the module's `test` command from `modules.yaml`
   on what it committed. On a failure the agent gets the output, plus the
   list of discarded paths, for up to two more rounds.
4. On a pass: pushes the branch, opens a pull request (it names changed
   dependency files and the discarded paths), comments on the ticket and
   moves it to Done. CI runs on the pull request.
5. On a failure: the work stays committed on the local branch; it comments
   on the ticket with the failing output and moves it back to Todo, or to
   Blocked on the second failure or rejection.
6. Either way it cleans the checkout and goes back to an up-to-date `main`.

While the agent works, the runner prints one line per step: what it says
(💬), each tool call (▶) and each failed tool result (✗), then the gate.
Each line starts with the event's time and the model's context at that step
(input plus cached tokens, e.g. `26.6k`).
The raw event stream is appended to `tools/kanban/logs/ticket-<n>.log`
(gitignored); `kanban.py log <n>` prints it the same way, with the
permission denials. `claude-local --resume <session>` (the id is printed
first) opens the whole conversation. If the runner itself crashes, the
ticket goes back to Todo.

`review` checks the branch out in a temporary worktree, so it never touches
the checkout you run it from.

`accept` waits for the pull request's checks and merges only when they are
green. A merge to `main` deploys.

## Running it overnight (on the inference box)
`loop` uses only the local model. It never reviews or merges; tickets pile
up in Done for a human (or `review`) in the morning. Run it in a herdr
terminal so it can be watched:

```bash
cd ~/scarcity            # a clone of this repo, on main
git pull -q
tools/kanban/kanban.py loop --poll 600 --limit 50
```

The box needs: `git`, `gh` logged in with the `project` scope
(`gh auth refresh -s project`) and push access to the repo, Node 24 with npm,
JDK 25 with `JAVA_HOME` set, Maven, Docker (Testcontainers in the Java
tests), and `claude-local` on the `PATH` (or `KANBAN_AGENT_CMD` naming the
equivalent command). A ticket whose run crashes the runner itself goes back
to Todo and is skipped for the rest of that loop.

## Settings
`KANBAN_AGENT_TIMEOUT` (seconds per agent turn, default 2700),
`KANBAN_TEST_TIMEOUT` (default 1500), `KANBAN_FIX_ROUNDS` (default 2),
`KANBAN_CI_TIMEOUT` (seconds `accept` waits for checks, default 2400),
`KANBAN_REVIEW_MODEL` (default `sonnet`), `KANBAN_AGENT_CMD` (default
`claude-local`), `KANBAN_MAX_CONTEXT` (tokens; an agent past it is stopped,
default 90000), `KANBAN_FRESH_CONTEXT` (tokens; a correction round after a
session this large starts fresh instead of resuming, default 60000).

## Guards
The small model degrades as its context fills and, after a failed guess, tends
to declare success and then repeat itself. So the runner stops an agent that
repeats one tool call 4 times, or a cycle of 2 or 3 calls 3 times, or whose
context passes `KANBAN_MAX_CONTEXT`. A correction round after such a run (or
after a large session) is a fresh session: the ticket prompt, a pointer to the
work already committed on the branch, and the gate report. The gate report
gives the failing tests' surefire/failsafe reports and the build's error lines,
with application log lines (ECS JSON) filtered out, not just the raw tail.
