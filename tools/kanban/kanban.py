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
    env = dict(os.environ)
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
    cmd = ["claude-local", "-p", prompt, "--output-format", "json", "--permission-mode", "acceptEdits",
           "--allowedTools"] + ALLOWED_TOOLS
    if session:
        cmd += ["--resume", session]
    code, out = sh(cmd, cwd=wt, check=False, timeout=AGENT_TIMEOUT, env=java_env())
    result, sid = out[-1500:], session
    for line in reversed(out.strip().splitlines()):
        try:
            data = json.loads(line)
            result, sid = str(data.get("result", ""))[-1500:], data.get("session_id", session)
            break
        except ValueError:
            continue
    return code, result, sid


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

    commit_all(t, wt, "%s: %s (#%d)" % (scope, t["title"], number))
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
                     "--title", "%s: %s (#%d)" % (scope, t["title"], number), "--body", body])
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
    code, checks = sh(["gh", "pr", "checks", str(pr), "--repo", REPO], check=False)
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


def cmd_loop(board, limit):
    done = 0
    while done < limit:
        board.reload()
        ready = sorted(n for n, it in board.items.items() if it["status"] == "Todo" and LABEL in it["labels"])
        if not ready:
            print("nothing in Todo with the '%s' label" % LABEL)
            return
        cmd_run(board, ready[0])
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
    p.add_argument("--limit", type=int, default=20)
    p = sub.add_parser("accept", help="merge a ticket's pull request and move the ticket to Accepted")
    p.add_argument("pr")
    p.add_argument("--force", action="store_true")
    p = sub.add_parser("reject", help="send a ticket back with a review")
    p.add_argument("pr")
    p.add_argument("reason")
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
        cmd_loop(board, a.limit)
    elif a.cmd == "accept":
        cmd_accept(board, a.pr, a.force)
    elif a.cmd == "reject":
        cmd_reject(board, a.pr, a.reason)


if __name__ == "__main__":
    main()
