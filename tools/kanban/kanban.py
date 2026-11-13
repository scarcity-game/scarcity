#!/usr/bin/env python3
"""Ticket runner: a local-model agent takes a ticket from Todo to Done.

See tools/kanban/README.md. Standard library only; needs `gh`, `git` and
`claude-local` on the PATH. Run it from the main checkout.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import textwrap
import time
from pathlib import Path

OWNER = "scarcity-game"
REPO = "scarcity-game/scarcity"
PROJECT = "1"
LABEL = "agent: local"
ROOT = Path(__file__).resolve().parents[2]
WORKTREES = ROOT / ".claude" / "worktrees"

AGENT_TIMEOUT = int(os.environ.get("KANBAN_AGENT_TIMEOUT", 45 * 60))
TEST_TIMEOUT = int(os.environ.get("KANBAN_TEST_TIMEOUT", 25 * 60))
FIX_ROUNDS = int(os.environ.get("KANBAN_FIX_ROUNDS", 2))
CI_TIMEOUT = int(os.environ.get("KANBAN_CI_TIMEOUT", 40 * 60))
REVIEW_MODEL = os.environ.get("KANBAN_REVIEW_MODEL", "sonnet")
# The local-model agent: Claude Code pointed at the LAN inference server. `run` and `loop`
# call nothing else; only `review` uses a Claude model (on the subscription).
AGENT_CMD = os.environ.get("KANBAN_AGENT_CMD", "claude-local")
MAX_BOUNCES = 2  # failed runs plus rejections before a ticket is Blocked

FAILED = "kanban: run failed"
REJECTED = "kanban: review rejected"

ALLOWED_TOOLS = [
    "Read", "Grep", "Glob", "Edit", "Write",
    "Bash(mvn:*)", "Bash(npm test:*)", "Bash(npm run:*)",
    "Bash(npx vitest:*)", "Bash(npx tsc:*)",
    "Bash(git status:*)", "Bash(git diff:*)", "Bash(git log:*)",
    "Bash(ls:*)", "Bash(cat:*)", "Bash(grep:*)", "Bash(tail:*)", "Bash(head:*)",
]


def sh(cmd, cwd=None, check=True, timeout=None, env=None):
    """Runs a command (a list), returns (exit code, combined output)."""
    try:
        p = subprocess.run(cmd, cwd=cwd, text=True, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, timeout=timeout, env=env)
        code, out = p.returncode, p.stdout
    except subprocess.TimeoutExpired as e:
        out = e.stdout or ""
        if isinstance(out, bytes):
            out = out.decode(errors="replace")
        code, out = 124, out + "\n[timed out after %ss]" % timeout
    except FileNotFoundError:
        code, out = 127, "%s: command not found" % cmd[0]
    if check and code != 0:
        sys.exit("command failed (%s): %s\n%s" % (code, " ".join(cmd), out[-2000:]))
    return code, out


def gh_json(args):
    return json.loads(sh(["gh"] + args)[1])


# ---------------------------------------------------------------- board

class Board:
    def __init__(self):
        fields = gh_json(["project", "field-list", PROJECT, "--owner", OWNER, "--format", "json"])
        status = next(f for f in fields["fields"] if f["name"] == "Status")
        self.field_id = status["id"]
        self.options = {o["name"]: o["id"] for o in status["options"]}
        self.project_id = gh_json(["project", "view", PROJECT, "--owner", OWNER, "--format", "json"])["id"]
        self.reload()

    def reload(self):
        data = gh_json(["project", "item-list", PROJECT, "--owner", OWNER, "--format", "json", "--limit", "500"])
        self.items = {}
        for it in data["items"]:
            number = (it.get("content") or {}).get("number")
            if number is not None:
                self.items[int(number)] = {
                    "id": it["id"], "status": it.get("status") or "", "title": it.get("title", ""),
                    "labels": it.get("labels") or [],
                }

    def add(self, number):
        if number not in self.items:
            # The item list lags a few seconds behind an add, so take the id from the add itself.
            item = gh_json(["project", "item-add", PROJECT, "--owner", OWNER, "--format", "json",
                            "--url", "https://github.com/%s/issues/%d" % (REPO, number)])
            self.items[number] = {"id": item["id"], "status": "", "title": item.get("title", ""), "labels": []}

    def move(self, number, status):
        self.add(number)
        sh(["gh", "project", "item-edit", "--id", self.items[number]["id"], "--project-id", self.project_id,
            "--field-id", self.field_id, "--single-select-option-id", self.options[status]])
        self.items[number]["status"] = status
        print("#%d -> %s" % (number, status))


# ---------------------------------------------------------------- tickets

def modules():
    """Module path -> {name, toolchain, test} from modules.yaml (simple parse, no YAML library)."""
    text = (ROOT / "modules.yaml").read_text()
    out = {}
    for block in re.split(r"\n  - name: ", text)[1:]:
        def field(key):
            m = re.search(r"^\s+%s: (.+)$" % key, block, re.M)
            return m.group(1).strip() if m else None
        path = field("path")
        if path:
            out[path] = {"name": block.split("\n", 1)[0].strip(), "toolchain": field("toolchain"),
                         "test": field("test")}
    return out


def ticket(number):
    issue = gh_json(["issue", "view", str(number), "--repo", REPO, "--json", "title,body,comments,labels,state"])
    m = re.search(r"^Module:\s*`?([\w./-]+)`?\s*$", issue["body"] or "", re.M)
    if not m:
        sys.exit("#%d has no 'Module: <path>' line" % number)
    path = m.group(1).strip("/")
    mods = modules()
    if path not in mods:
        sys.exit("#%d: module %s is not in modules.yaml" % (number, path))
    comments = [c["body"] for c in issue["comments"]]
    return {
        "number": number, "title": issue["title"], "body": issue["body"], "module": path,
        "mod": mods[path],
        "reviews": [c for c in comments if c.startswith(REJECTED) or c.startswith(FAILED)],
    }


def bounces(t):
    return len(t["reviews"])


# ---------------------------------------------------------------- the run

def java_env():
    """JAVA_HOME from the environment if set (Linux), else macOS's java_home for JDK 25."""
    env = dict(os.environ)
    if not env.get("JAVA_HOME") and Path("/usr/libexec/java_home").exists():
        code, home = sh(["/usr/libexec/java_home", "-v", "25"], check=False)
        if code == 0:
            env["JAVA_HOME"] = home.strip()
    return env


def worktree_for(number):
    wt = WORKTREES / ("ticket-%d" % number)
    branch = "ticket/%d" % number
    sh(["git", "fetch", "-q", "origin"], cwd=ROOT)
    if not wt.exists():
        exists = sh(["git", "rev-parse", "--verify", "-q", branch], cwd=ROOT, check=False)[0] == 0
        if exists:
            sh(["git", "worktree", "add", "-q", str(wt), branch], cwd=ROOT)
        else:
            sh(["git", "worktree", "add", "-q", "-b", branch, str(wt), "origin/main"], cwd=ROOT)
    return wt, branch


def prompt_for(t, wt):
    test = t["mod"]["test"]
    reviews = ""
    if t["reviews"]:
        reviews = "\n\nEarlier attempts at this ticket were sent back. Fix what these say:\n\n" + \
                  "\n\n---\n\n".join(t["reviews"][-2:])
    return textwrap.dedent("""\
        You are working alone on one ticket in the Scarcity monorepo. Your working directory is a
        git worktree of the repo: {wt}

        Rules:
        - Change files only under `{module}/`. Read anything you need anywhere in the repo.
        - First read `AGENTS.md` (repo root), `{module}/AGENTS.md`, and every file the ticket names.
        - Write or update tests for what you change. Do not delete or weaken existing tests.
        - Run the module's tests yourself and keep going until they pass. From `{module}/` the full
          command is: `{test}`. While iterating, run only the test files you touched.
        - Do not add dependencies. Do not run `git commit`, `git push` or `npm install <package>`.
        - If the ticket changes behaviour that `{module}/AGENTS.md` or `{module}/README.md` describes,
          update them.
        - When the full test command passes, stop and reply with a line starting `RESULT:` that
          says in two or three sentences what you changed. If you cannot finish, reply with a line
          starting `BLOCKED:` and the reason.

        Ticket #{number}: {title}

        {body}{reviews}
        """).format(wt=wt, module=t["module"], test=test, number=t["number"], title=t["title"],
                    body=t["body"], reviews=reviews)


def run_agent(prompt, wt, session=None):
    cmd = [AGENT_CMD, "-p", prompt, "--output-format", "json", "--permission-mode", "acceptEdits",
           "--allowedTools"] + ALLOWED_TOOLS
    if session:
        cmd += ["--resume", session]
    code, out = sh(cmd, cwd=wt, check=False, timeout=AGENT_TIMEOUT, env=java_env())
    log_agent(wt, out)
    final = result_event(out)
    if final is None:
        return code, out[-1500:], session
    stats = "turns %s, %ss" % (final.get("num_turns", "?"), int(final.get("duration_ms", 0)) // 1000)
    return code, str(final.get("result", ""))[-1500:] + "\n(" + stats + ")", final.get("session_id", session)


def result_event(out):
    """The final `result` object of `claude -p --output-format json`: one object, or a list of
    events (newer versions), possibly after other lines of output."""
    for line in reversed(out.strip().splitlines()):
        try:
            data = json.loads(line)
        except ValueError:
            continue
        if isinstance(data, list):
            data = next((e for e in reversed(data) if isinstance(e, dict) and e.get("type") == "result"), None)
        if isinstance(data, dict):
            return data
    return None


def log_agent(wt, out):
    """Keeps every agent turn's raw output next to the worktree, for diagnosing a bad run."""
    log = WORKTREES / (wt.name + ".log")
    with log.open("a") as f:
        f.write(out + "\n")


def gate(t, wt):
    """The mechanical gate. Returns (ok, report)."""
    module = t["module"]
    _, status = sh(["git", "status", "--porcelain", "--untracked-files=all"], cwd=wt)
    changed = [line[3:].split(" -> ")[-1].strip('"') for line in status.splitlines() if line.strip()]
    _, ahead = sh(["git", "rev-list", "--count", "origin/main..HEAD"], cwd=wt)
    if not changed and ahead.strip() == "0":
        return False, "The agent changed no files."
    outside = [p for p in changed if not p.startswith(module + "/")]
    if outside:
        return False, "Files changed outside `%s/`:\n%s\nRevert them." % (module, "\n".join(outside))
    code, out = sh(["bash", "-lc", t["mod"]["test"]], cwd=wt / module, check=False,
                   timeout=TEST_TIMEOUT, env=java_env())
    tail = "\n".join(out.strip().splitlines()[-60:])
    if code != 0:
        return False, "`%s` failed (exit %s). Last lines:\n```\n%s\n```" % (t["mod"]["test"], code, tail)
    return True, "\n".join(out.strip().splitlines()[-8:])


def title_of(t):
    """`<module>: <title> (#n)`, without doubling a scope the issue title already has."""
    title = re.sub(r"^[\w/ -]+:\s*", "", t["title"], count=1)
    return "%s: %s (#%d)" % (t["mod"]["name"], title, t["number"])


def commit_all(t, wt, message):
    sh(["git", "add", "-A", "--", t["module"]], cwd=wt)
    if sh(["git", "diff", "--cached", "--quiet"], cwd=wt, check=False)[0] != 0:
        sh(["git", "commit", "-q", "-m", message], cwd=wt)


def cmd_run(board, number, force=False):
    board.add(number)
    status = board.items[number]["status"]
    if status != "Todo" and not force:
        sys.exit("#%d is in '%s', not Todo (use --force)" % (number, status))
    t = ticket(number)
    board.move(number, "Doing")
    try:
        return run_ticket(board, t)
    except BaseException as e:
        # A crash of the runner itself (not the agent) must not leave the ticket in Doing.
        if board.items[number]["status"] == "Doing":
            board.move(number, "Todo")
        if isinstance(e, Exception):
            sys.exit("runner crashed on #%d (ticket moved back to Todo): %r" % (number, e))
        raise


def run_ticket(board, t):
    number = t["number"]
    wt, branch = worktree_for(number)
    if t["mod"]["toolchain"] == "node" and not (wt / t["module"] / "node_modules").exists():
        sh(["npm", "ci", "--silent"], cwd=wt / t["module"], timeout=600)

    code, result, sid = run_agent(prompt_for(t, wt), wt)
    print("agent finished (exit %s): %s" % (code, result[-300:]))
    ok, report = gate(t, wt)
    rounds = 0
    while not ok and rounds < FIX_ROUNDS and sid:
        rounds += 1
        print("gate failed; correction round %d" % rounds)
        code, result, sid = run_agent(
            "The work is not done. An automatic check failed:\n\n%s\n\nFix it, run the tests again, "
            "and reply with a `RESULT:` line when the full test command passes." % report, wt, sid)
        ok, report = gate(t, wt)

    scope = t["mod"]["name"]
    if not ok:
        commit_all(t, wt, "%s: work in progress on #%d (gate failed)" % (scope, number))
        sh(["gh", "issue", "comment", str(number), "--repo", REPO, "--body",
            "%s after %d correction round(s).\n\n%s\n\nAgent's last words:\n> %s"
            % (FAILED, rounds, report, result[-600:].replace("\n", "\n> "))])
        board.move(number, "Blocked" if bounces(t) + 1 >= MAX_BOUNCES else "Todo")
        return False

    commit_all(t, wt, title_of(t))
    sh(["git", "push", "-q", "-u", "origin", branch, "--force-with-lease"], cwd=wt)
    _, names = sh(["git", "diff", "--name-only", "origin/main...HEAD"], cwd=wt)
    deps = [n for n in names.split() if n.endswith(("package.json", "package-lock.json", "pom.xml"))]
    body = "Refs #%d.\n\nMade by the local-model ticket runner (`tools/kanban`). The module's test command passed " \
           "on this branch before the pull request was opened (%d correction round(s)).\n\n" \
           "Agent's summary:\n> %s\n\n```\n%s\n```\n%s" % (
               number, rounds, result[-800:].replace("\n", "\n> "), report,
               ("\n**Dependency files changed: %s**\n" % ", ".join(deps)) if deps else "")
    code, url = sh(["gh", "pr", "view", branch, "--repo", REPO, "--json", "url", "--jq", ".url"], check=False)
    if code != 0:
        _, url = sh(["gh", "pr", "create", "--repo", REPO, "--head", branch, "--base", "main",
                     "--title", title_of(t), "--body", body])
    url = url.strip().splitlines()[-1]
    sh(["gh", "issue", "comment", str(number), "--repo", REPO, "--body",
        "kanban: pull request %s is open; the module's tests passed locally (%d correction round(s))."
        % (url, rounds)])
    board.move(number, "Done")
    print(url)
    return True


def issue_of(pr):
    data = gh_json(["pr", "view", str(pr), "--repo", REPO, "--json", "headRefName,number,url"])
    m = re.match(r"ticket/(\d+)$", data["headRefName"])
    if not m:
        sys.exit("pull request %s is not a ticket branch" % pr)
    return int(m.group(1)), data


def cleanup(number):
    wt = WORKTREES / ("ticket-%d" % number)
    if wt.exists():
        sh(["git", "worktree", "remove", "--force", str(wt)], cwd=ROOT, check=False)
    sh(["git", "branch", "-D", "ticket/%d" % number], cwd=ROOT, check=False)


def cmd_accept(board, pr, force=False):
    number, data = issue_of(pr)
    # Waits for pending checks; a non-zero exit then means a check failed (or none ran).
    code, checks = sh(["gh", "pr", "checks", str(pr), "--repo", REPO, "--watch", "--interval", "30"],
                      check=False, timeout=CI_TIMEOUT)
    if code != 0 and not force:
        sys.exit("checks are not all green on %s (use --force to merge anyway):\n%s" % (data["url"], checks))
    sh(["gh", "pr", "merge", str(pr), "--repo", REPO, "--squash", "--delete-branch"])
    sh(["gh", "issue", "close", str(number), "--repo", REPO, "--reason", "completed",
        "--comment", "kanban: accepted and merged in %s." % data["url"]])
    board.move(number, "Accepted")
    cleanup(number)


def cmd_reject(board, pr, reason):
    number, data = issue_of(pr)
    sh(["gh", "issue", "comment", str(number), "--repo", REPO, "--body", "%s (%s)\n\n%s" % (REJECTED, data["url"], reason)])
    sh(["gh", "pr", "comment", str(pr), "--repo", REPO, "--body", "Sent back:\n\n" + reason])
    t = ticket(number)
    board.move(number, "Blocked" if bounces(t) >= MAX_BOUNCES else "Todo")


REVIEW_PROMPT = """\
You review one pull request made by a weak local model for a ticket in the Scarcity monorepo.
Your working directory is a checkout of the pull request's branch. You are read-only: do not edit
files and do not run tests (the runner already ran the module's test command and it passed; CI
runs it again).

Read `AGENTS.md` (repo root), `{module}/AGENTS.md`, the files and contract sections the ticket
names, and whatever the diff touches. Then judge the diff against these, in order:
1. Every acceptance item of the ticket is met. Check each one against the code, not the summary.
2. It follows the contracts in `docs/` that apply, and the module's `AGENTS.md`.
3. No test is removed, skipped or weakened; new behaviour has a test that would fail without it.
4. Nothing outside the ticket's scope: no unrelated rewrites, no reformatting, no new dependency.
5. No bug you can point to: a concrete input or state that gives a wrong result.
Style preferences are not reasons to reject.

Every problem you list must rest on something you checked in the files, not on the diff alone
or on one example. A claim about "the existing code" (its style, a convention, how something is
done elsewhere) needs a count over the module with Grep, stated in the review ("12 of 14 files
do X"). If the evidence is mixed, it is not a reason to reject. When unsure, ACCEPT and say what
a human should look at.

Reply with the review, then a last line that is exactly `VERDICT: ACCEPT` or `VERDICT: REJECT`.
On REJECT, list each problem as a bullet with `file:line` and what to change, concretely enough
that the weak model can fix it without asking. Keep the review under 300 words.

Ticket #{number}: {title}

{body}

Diff (`git diff origin/main...HEAD`):
```diff
{diff}
```
"""


def review_env():
    # The reviewer runs on the subscription, never on the local endpoint.
    env = dict(os.environ)
    for k in ("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY"):
        env.pop(k, None)
    return env


def cmd_review(board, pr, apply=False):
    number, data = issue_of(pr)
    t = ticket(number)
    wt, branch = worktree_for(number)
    sh(["git", "fetch", "-q", "origin", branch], cwd=wt)
    sh(["git", "checkout", "-q", "--detach", "origin/" + branch], cwd=wt)
    _, diff = sh(["git", "diff", "origin/main...HEAD"], cwd=wt)
    if len(diff) > 60000:
        diff = diff[:60000] + "\n[diff cut at 60000 characters; read the files]"
    prompt = REVIEW_PROMPT.format(module=t["module"], number=number, title=t["title"], body=t["body"], diff=diff)
    code, out = sh(["claude", "-p", prompt, "--model", REVIEW_MODEL, "--output-format", "json",
                    "--allowedTools", "Read", "Grep", "Glob", "Bash(git diff:*)", "Bash(git log:*)",
                    "Bash(git show:*)"], cwd=wt, check=False, timeout=AGENT_TIMEOUT, env=review_env())
    sh(["git", "checkout", "-q", branch], cwd=wt, check=False)
    final = result_event(out)
    text = str((final or {}).get("result", "")).strip()
    m = re.search(r"^VERDICT:\s*(ACCEPT|REJECT)\s*$", text, re.M)
    if code != 0 or not m:
        sys.exit("review of %s gave no verdict (exit %s):\n%s" % (data["url"], code, (text or out)[-1500:]))
    verdict, review = m.group(1), text[:m.start()].strip()
    sh(["gh", "pr", "comment", str(pr), "--repo", REPO, "--body",
        "kanban: review by `%s`: **%s**\n\n%s" % (REVIEW_MODEL, verdict, review)])
    print("%s %s\n\n%s" % (data["url"], verdict, review))
    if apply:
        if verdict == "ACCEPT":
            cmd_accept(board, pr)
        else:
            cmd_reject(board, pr, review)
    return verdict


def cmd_loop(board, limit, poll):
    """Works Todo tickets one after another. With --poll, waits for new ones instead of stopping.
    Uses only the local model: no review, no merge."""
    done, crashed = 0, set()
    while done < limit:
        board.reload()
        ready = sorted(n for n, it in board.items.items()
                       if it["status"] == "Todo" and LABEL in it["labels"] and n not in crashed)
        if not ready:
            if not poll:
                print("nothing in Todo with the '%s' label" % LABEL)
                return
            print("%s  nothing to do; next look in %ds" % (time.strftime("%H:%M"), poll), flush=True)
            time.sleep(poll)
            continue
        number = ready[0]
        print("%s  ticket #%d" % (time.strftime("%H:%M"), number), flush=True)
        try:
            cmd_run(board, number)
        except SystemExit as e:
            # The runner itself failed (gh, git, npm ci...). The ticket is back in Todo; don't
            # pick it again in this loop, a human has to look.
            print("#%d: %s" % (number, e), flush=True)
            crashed.add(number)
        done += 1


def cmd_status(board):
    for name in board.options:
        rows = sorted((n, it) for n, it in board.items.items() if it["status"] == name)
        print("%s (%d)" % (name, len(rows)))
        for n, it in rows:
            print("  #%d %s" % (n, it["title"]))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status", help="print the board")
    p = sub.add_parser("add", help="put issues on the board")
    p.add_argument("issues", type=int, nargs="+")
    p.add_argument("--to", default="Backlog")
    p = sub.add_parser("move", help="move an issue to a column")
    p.add_argument("issue", type=int)
    p.add_argument("status")
    p = sub.add_parser("run", help="have a local agent work one Todo ticket")
    p.add_argument("issue", type=int)
    p.add_argument("--force", action="store_true")
    p = sub.add_parser("loop", help="work Todo tickets one after another")
    p.add_argument("--limit", type=int, default=20, help="tickets to work before stopping")
    p.add_argument("--poll", type=int, default=0, metavar="SECONDS",
                   help="when Todo is empty, look again after this long instead of stopping")
    p = sub.add_parser("accept", help="merge a ticket's pull request and move the ticket to Accepted")
    p.add_argument("pr")
    p.add_argument("--force", action="store_true")
    p = sub.add_parser("reject", help="send a ticket back with a review")
    p.add_argument("pr")
    p.add_argument("reason")
    p = sub.add_parser("review", help="have a frontier model review a ticket's pull request")
    p.add_argument("pr")
    p.add_argument("--apply", action="store_true", help="accept or reject on the verdict (a merge deploys)")
    a = ap.parse_args()

    board = Board()
    if a.cmd == "status":
        cmd_status(board)
    elif a.cmd == "add":
        for n in a.issues:
            board.move(n, a.to)
    elif a.cmd == "move":
        board.move(a.issue, a.status)
    elif a.cmd == "run":
        sys.exit(0 if cmd_run(board, a.issue, a.force) else 1)
    elif a.cmd == "loop":
        cmd_loop(board, a.limit, a.poll)
    elif a.cmd == "accept":
        cmd_accept(board, a.pr, a.force)
    elif a.cmd == "reject":
        cmd_reject(board, a.pr, a.reason)
    elif a.cmd == "review":
        cmd_review(board, a.pr, a.apply)


if __name__ == "__main__":
    main()
