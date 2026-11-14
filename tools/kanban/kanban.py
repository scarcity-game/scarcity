#!/usr/bin/env python3
"""Ticket runner: a local-model agent takes a ticket from Todo to Done.

See tools/kanban/README.md. Standard library only; needs `gh`, `git` and
`claude-local` on the PATH. Run it from the main checkout.
"""
import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import tempfile
import textwrap
import threading
import time
from pathlib import Path

OWNER = "scarcity-game"
REPO = "scarcity-game/scarcity"
PROJECT = "1"
LABEL = "agent: local"
ROOT = Path(__file__).resolve().parents[2]
LOGS = ROOT / "tools" / "kanban" / "logs"  # gitignored, so a clean of the checkout keeps them
OLD_WORKTREES = ROOT / ".claude" / "worktrees"  # where earlier versions ran tickets

AGENT_TIMEOUT = int(os.environ.get("KANBAN_AGENT_TIMEOUT", 45 * 60))
TEST_TIMEOUT = int(os.environ.get("KANBAN_TEST_TIMEOUT", 25 * 60))
FIX_ROUNDS = int(os.environ.get("KANBAN_FIX_ROUNDS", 2))
CI_TIMEOUT = int(os.environ.get("KANBAN_CI_TIMEOUT", 40 * 60))
REVIEW_MODEL = os.environ.get("KANBAN_REVIEW_MODEL", "sonnet")
# The local-model agent: Claude Code pointed at the LAN inference server. `run` and `loop`
# call nothing else; only `review` uses a Claude model (on the subscription).
AGENT_CMD = os.environ.get("KANBAN_AGENT_CMD", "claude-local")
MAX_BOUNCES = 2  # failed runs plus rejections before a ticket is Blocked
# The small model degrades as its context fills: past about 100k tokens it starts copying its
# own last turn. A run is stopped at MAX_CONTEXT, and a correction round starts a fresh session
# (instead of resuming) once the last one went past FRESH_CONTEXT or was stopped.
MAX_CONTEXT = int(os.environ.get("KANBAN_MAX_CONTEXT", 90_000))
FRESH_CONTEXT = int(os.environ.get("KANBAN_FRESH_CONTEXT", 60_000))

FAILED = "kanban: run failed"
REJECTED = "kanban: review rejected"

# The agent gets only these tools (no subagents, web, MCP servers or worktree tools: each
# costs the small model context and none helps a ticket). Within them it may do anything
# (whole repo, temp files, installs) except the git and gh commands that are the runner's job.
AGENT_TOOLS = "Read,Grep,Glob,Edit,Write,Bash"
DENIED = ["Bash(git %s:*)" % c for c in
          ("commit", "push", "checkout", "switch", "reset", "rebase", "merge", "stash", "worktree")] + \
         ["Bash(gh:*)"]


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


# The runner works tickets in the main checkout, which is its own: one ticket at a time, on
# branch ticket/<n>, and back on a clean, up-to-date main afterwards.

def git(*args, check=True):
    return sh(["git"] + list(args), cwd=ROOT, check=check)[1].strip()


def local_changes():
    """Changed tracked files and untracked files that are not ignored, as paths."""
    out = sh(["git", "status", "--porcelain", "--untracked-files=all"], cwd=ROOT)[1]
    return [line[3:].split(" -> ")[-1].strip('"') for line in out.splitlines() if line.strip()]


def clean_checkout():
    """Drops every change and untracked file. Ignored files (node_modules, target/, logs) stay."""
    git("reset", "-q", "--hard")
    git("clean", "-q", "-fd")


def start_branch(number):
    """Cleans up after any earlier ticket and checks out ticket/<n>, new from origin/main or as
    an earlier attempt left it. Refuses to touch changes on a branch that is not a ticket's."""
    branch = "ticket/%d" % number
    current = git("rev-parse", "--abbrev-ref", "HEAD")
    if local_changes() and not current.startswith("ticket/"):
        sys.exit("the checkout has local changes on %s; the runner resets it, so commit or move "
                 "them first:\n%s" % (current, "\n".join(local_changes()[:20])))
    clean_checkout()
    old = OLD_WORKTREES / ("ticket-%d" % number)
    if old.exists():  # a worktree from an earlier version of the runner holds the branch
        git("worktree", "remove", "--force", str(old), check=False)
    git("fetch", "-q", "origin")
    if sh(["git", "rev-parse", "--verify", "-q", branch], cwd=ROOT, check=False)[0] == 0:
        git("switch", "-q", branch)
    elif sh(["git", "rev-parse", "--verify", "-q", "origin/" + branch], cwd=ROOT, check=False)[0] == 0:
        git("switch", "-q", "-c", branch, "origin/" + branch)
    else:
        git("switch", "-q", "-c", branch, "origin/main")
    return branch


def back_to_main():
    clean_checkout()
    git("switch", "-q", "main")
    git("merge", "-q", "--ff-only", "origin/main", check=False)


def install(t):
    """Installs the module's dependencies fresh, so the agent starts from a working tree."""
    if t["mod"]["toolchain"] != "node":
        return
    d = ROOT / t["module"]
    code, out = sh(["npm", "ci", "--no-audit", "--no-fund"], cwd=d, check=False, timeout=900)
    if code != 0 or not (d / "node_modules" / ".bin").exists():
        sys.exit("npm ci in %s failed (exit %s):\n%s" % (t["module"], code, out[-1500:]))


def hints(t):
    """Commands for the prompt. Test output always goes to a file that the agent greps: a full
    run prints hundreds of KB (other modules' tests, services' ECS logs), and the agent's Bash
    tool keeps only the first ~10k characters of it, which never reach this module's results."""
    module, d, n = t["module"], ROOT / t["module"], t["number"]
    log = "/tmp/kanban-%d/test.log" % n
    run = lambda cmd: "`mkdir -p /tmp/kanban-%d && cd %s && { %s; } > %s 2>&1; echo \"exit $?\"`" % (n, d, cmd, log)
    if t["mod"]["toolchain"] == "node":
        return {"full": run(t["mod"]["test"]),
                "one": run("npx vitest run src/path/to/File.test.tsx"),
                "grep": "`grep -nE 'FAIL|×|✗|Error|error TS|Tests +[0-9]|Test Files' %s | head -60`" % log,
                "detail": "`grep -n -A30 'FAIL' %s | head -120`" % log,
                "install": "`cd %s && npm install <package>` (it updates package.json and the lock file)" % d}
    return {"full": run(t["mod"]["test"]),
            "one": run("mvn -B -f %s/pom.xml -pl %s -am test -Dtest=SomeTest "
                       "-Dsurefire.failIfNoSpecifiedTests=false" % (ROOT, module)),
            "grep": "`grep -nE 'Tests run:|<<< (FAILURE|ERROR)|BUILD (SUCCESS|FAILURE)|^\\[ERROR\\]' %s "
                    "| grep -v 'Help 1\\|-run Maven\\|^[0-9]*:\\[ERROR\\] *$' | head -60`" % log,
            "detail": "`grep -l '<<< FAIL\\|<<< ERROR' %s/*/*/target/surefire-reports/*.txt` lists the "
                      "failing test classes' reports; `head -80` one of them for the assertion and stack "
                      "trace" % ROOT,
            "install": "add it to `%s/pom.xml`" % module}


def prompt_for(t):
    module, d = t["module"], ROOT / t["module"]
    reviews = ""
    if t["reviews"]:
        reviews = "\n\nEarlier attempts at this ticket were sent back. Fix what these say:\n\n" + \
                  "\n\n---\n\n".join(t["reviews"][-2:])
    h = hints(t)
    return textwrap.dedent("""\
        You are working alone on one ticket in the Scarcity monorepo. Your working directory is the
        repo root, {root}, on git branch `ticket/{number}`. Nobody else uses this checkout: read,
        write and run whatever you need anywhere in it.

        The ticket is for the module `{module}/`. Its directory is {dir}, and its commands run from
        there. Put the `cd` in the same Bash call.

        Running tests. Test output is far too long to read directly: your Bash tool only shows
        the first part of it, which is other modules' build output, never this module's results.
        So always send it to a file, then grep the file. Use these commands exactly:
        - Full test command (`{test}`): {full}
        - One test while you iterate: {one}
        - Then read the results: {grep}
        - Details of a failure: {detail}
        `exit 0` means it passed. Never judge a run from the first lines of its output, and never
        run the test command without the redirect.

        - Dependencies are installed. If the ticket needs a new one, {install}.
        - Scratch files (notes, quick scripts, experiments): use /tmp/kanban-{number}/.

        What is kept: when you stop, the runner commits the changes under `{module}/` and throws
        away everything else in the checkout. So the ticket's work goes under `{module}/` and
        nothing else; edits elsewhere only help you while you work. Do not leave scratch files
        under `{module}/`, or they are committed. The runner makes the commits and switches
        branches: `git commit`, `push`, `checkout`, `switch`, `reset`, `merge` and `gh` are blocked.
        Use `git diff`, `git status`, `git log` and `git restore <file>` freely.

        Rules:
        - First read `AGENTS.md`, `{module}/AGENTS.md`, and every file the ticket names.
        - Write or update tests for what you change. Do not delete or weaken existing tests.
        - Run the full test command and keep going until it passes.
        - If the ticket changes behaviour that `{module}/AGENTS.md` or `{module}/README.md`
          describes, update them.
        - When the full test command passes, stop and reply with a line starting `RESULT:` that
          says in two or three sentences what you changed. If you cannot finish, reply with a line
          starting `BLOCKED:` and the reason.

        Ticket #{number}: {title}

        {body}{reviews}
        """).format(root=ROOT, dir=d, module=module, test=t["mod"]["test"], number=t["number"],
                    title=t["title"], body=t["body"], reviews=reviews, **h)


def log_path(number):
    LOGS.mkdir(parents=True, exist_ok=True)
    return LOGS / ("ticket-%d.log" % number)


class Run:
    """What one agent run did: exit code, its final words, session id, the largest context it
    reached, and why the runner stopped it early (None if it wasn't)."""
    def __init__(self, code, result, session, context=0, stopped=None):
        self.code, self.result, self.session, self.context, self.stopped = code, result, session, context, stopped


def call_signature(c):
    return c.get("name", "") + " " + json.dumps(c.get("input") or {}, sort_keys=True)


def looping(calls):
    """True when the latest tool calls are one call repeated 4 times, or a cycle of 2 or 3
    calls repeated 3 times: the model is copying itself, not working."""
    for period, times in ((1, 4), (2, 3), (3, 3)):
        n = period * times
        if len(calls) >= n and all(calls[-i] == calls[-i - period] for i in range(1, n - period + 1)):
            return True
    return False


def watch_event(line, calls):
    """Updates the list of tool-call signatures from one raw output line; returns the context
    size (tokens) of an assistant message in it, or 0."""
    try:
        data = json.loads(line)
    except ValueError:
        return 0
    context = 0
    for e in data if isinstance(data, list) else [data]:
        if not isinstance(e, dict) or e.get("type") != "assistant":
            continue
        message = e.get("message") or {}
        u = message.get("usage") or {}
        context = max(context, sum(u.get(k) or 0 for k in
                                   ("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")))
        calls += [call_signature(c) for c in message.get("content") or [] if c.get("type") == "tool_use"]
    return context


def run_agent(prompt, number, session=None):
    """Runs one agent turn, printing a line per step as it happens. Raw events go to the log.
    Stops the agent when it loops or its context passes MAX_CONTEXT."""
    cmd = [AGENT_CMD, "-p", prompt, "--output-format", "stream-json", "--verbose",
           "--permission-mode", "bypassPermissions", "--tools", AGENT_TOOLS, "--strict-mcp-config",
           "--disallowedTools"] + DENIED
    if session:
        cmd += ["--resume", session]
    log = log_path(number).open("a")
    lines = []
    # No auto-memory: ~/.claude outlives the checkout reset, so a memory from one ticket (often
    # a false "done") would be loaded into the next ones.
    env = dict(java_env(), CLAUDE_CODE_DISABLE_AUTO_MEMORY="1")
    try:
        p = subprocess.Popen(cmd, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             env=env)
    except FileNotFoundError:
        return Run(127, "%s: command not found" % AGENT_CMD, session)
    stopped = []
    timer = threading.Timer(AGENT_TIMEOUT, lambda: (stopped.append("timed out after %ss" % AGENT_TIMEOUT),
                                                    p.kill()))
    timer.start()
    calls, context = [], 0
    try:
        for line in p.stdout:
            log.write(line)
            log.flush()
            lines.append(line)
            show_event(line)
            context = max(context, watch_event(line, calls))
            if stopped:
                continue
            if looping(calls):
                stopped.append("looping: it repeated the same tool call(s): %s" % short(calls[-1], 150))
            elif context > MAX_CONTEXT:
                stopped.append("its context passed %d tokens" % MAX_CONTEXT)
            if stopped:
                p.kill()
        code = p.wait()
    finally:
        timer.cancel()
        log.close()
    if stopped:
        code = 124
        print("  [agent stopped: %s]" % stopped[0], flush=True)
    stop = stopped[0] if stopped else None
    out = "".join(lines)
    final = result_event(out)
    if final is None:
        return Run(code, out[-1500:], session, context, stop)
    stats = "turns %s, %ss" % (final.get("num_turns", "?"), int(final.get("duration_ms", 0)) // 1000)
    return Run(code, str(final.get("result", ""))[-1500:] + "\n(" + stats + ")",
               final.get("session_id", session), context, stop)


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


def short(text, n=110):
    text = " ".join(str(text).split())
    return text if len(text) <= n else text[:n - 1] + "…"


TOOL_ARG = {"Read": "file_path", "Edit": "file_path", "Write": "file_path", "Bash": "command",
            "Grep": "pattern", "Glob": "pattern"}


def stamp_of(e, live=True):
    """Local HH:MM:SS of an event's own `timestamp`; when it has none, now (live) or blank."""
    ts = e.get("timestamp") if isinstance(e, dict) else None
    if isinstance(ts, str):
        try:
            when = datetime.datetime.fromisoformat(ts.replace("Z", "+00:00"))
            return when.astimezone().strftime("%H:%M:%S")
        except ValueError:
            pass
    return time.strftime("%H:%M:%S") if live else " " * 8


def context_of(message):
    """Tokens in the model's context for this message (`  18.1k`), from its usage."""
    u = (message or {}).get("usage") or {}
    n = sum(u.get(k) or 0 for k in ("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens"))
    return "%5.1fk" % (n / 1000.0) if n else "     -"


def event_lines(line, live=True):
    """One short line per agent step in a raw output line, each starting with the event's time
    and the context size: what it says (💬), which tool it calls (▶), failed tool results (✗),
    permission denials. A line may hold one event or, from some Claude Code versions, a JSON
    list of all of them."""
    try:
        data = json.loads(line)
    except ValueError:
        return ["%s        %s" % (stamp_of(None, live), short(line))] if line.strip() else []
    out = []
    for e in data if isinstance(data, list) else [data]:
        if not isinstance(e, dict):
            continue
        kind, stamp = e.get("type"), stamp_of(e, live)
        ctx = context_of(e.get("message")) if kind == "assistant" else "      "
        add = lambda text: out.append("%s %s %s" % (stamp, ctx, text))
        if kind == "system" and e.get("subtype") == "init":
            add("session %s (%s) in %s" % (e.get("session_id"), e.get("model"), e.get("cwd")))
        elif kind == "assistant":
            for c in (e.get("message") or {}).get("content") or []:
                if c.get("type") == "text" and c.get("text", "").strip():
                    add("💬 " + short(c["text"]))
                elif c.get("type") == "tool_use":
                    args = c.get("input") or {}
                    arg = args.get(TOOL_ARG.get(c.get("name"), ""), "") or json.dumps(args)
                    add("▶ %s %s" % (c.get("name"), short(arg, 100)))
        elif kind == "user":
            content = (e.get("message") or {}).get("content")
            for c in content if isinstance(content, list) else []:
                if isinstance(c, dict) and c.get("type") == "tool_result" and c.get("is_error"):
                    body = c.get("content")
                    if isinstance(body, list):
                        body = " ".join(str(b.get("text", "")) for b in body if isinstance(b, dict))
                    add("✗ " + short(body))
        elif kind == "result":
            u = e.get("usage") or {}
            add("done: %s turns, %ss, %d permission denial(s); tokens in %s, cached %s, out %s" % (
                e.get("num_turns"), int(e.get("duration_ms", 0)) // 1000,
                len(e.get("permission_denials") or []), u.get("input_tokens", "?"),
                u.get("cache_read_input_tokens", "?"), u.get("output_tokens", "?")))
            for d in e.get("permission_denials") or []:
                args = d.get("tool_input") or {}
                arg = args.get(TOOL_ARG.get(d.get("tool_name"), ""), "") or json.dumps(args)
                add("  denied: %s %s" % (d.get("tool_name"), short(arg, 100)))
    return out


def show_event(line):
    for text in event_lines(line):
        print("  " + text, flush=True)


def gate(t, message):
    """The mechanical gate. Commits the changes under the module (with `message`), throws away
    everything else, then runs the module's test command on exactly what was committed.
    Returns (ok, report, paths outside the module that were thrown away)."""
    module = t["module"]
    outside = [p for p in local_changes() if not p.startswith(module + "/")]
    commit_all(t, message)
    clean_checkout()
    if git("rev-list", "--count", "origin/main..HEAD") == "0":
        return False, "Nothing changed under `%s/`." % module, outside
    print("%s  gate: %s (in %s/)" % (time.strftime("%H:%M:%S"), t["mod"]["test"], module), flush=True)
    started = time.time()
    code, out = sh(["bash", "-lc", t["mod"]["test"]], cwd=ROOT / module, check=False,
                   timeout=TEST_TIMEOUT, env=java_env())
    if code != 0:
        return False, failure_report(t["mod"]["test"], code, out, started), outside
    return True, "\n".join(out.strip().splitlines()[-8:]), outside


# Lines of a failed build that say what failed, for Maven (surefire/failsafe) and npm (vitest,
# tsc, vite, playwright). Everything else is application log noise for the agent.
FAILURE_LINE = re.compile(
    r"\[ERROR\]|BUILD FAILURE|Tests run:.*(Failures: [1-9]|Errors: [1-9])|<<< (FAILURE|ERROR)!"
    r"|^\s*(FAIL|×|✗|✘)\s|AssertionError|AssertionFailedError|Expected|Received|error TS\d+"
    r"|npm ERR!|^\s*\d+ (failed|flaky)|Error: ", re.M)
REPORT_CHARS = 6000


def is_log_noise(line):
    """An application log line (ECS JSON from a service under test), not build output."""
    s = line.lstrip()
    return s.startswith("{") and ('"@timestamp"' in s or '"log.level"' in s or '"log":' in s)


def failure_report(command, code, out, started):
    """What the agent needs from a failed test command: the failing tests' surefire/failsafe
    reports, the build's error lines, and the tail of the build output without log noise."""
    lines = [l for l in out.strip().splitlines() if not is_log_noise(l)]
    noise = len(out.strip().splitlines()) - len(lines)
    errors, seen = [], set()
    for l in lines:
        if FAILURE_LINE.search(l) and l not in seen:
            seen.add(l)
            errors.append(l)
    parts = ["`%s` failed (exit %s)." % (command, code)]
    reports = failed_test_reports(started)
    if reports:
        parts.append("Failing tests (from their surefire/failsafe reports):")
        parts += ["%s:\n```\n%s\n```" % (p.relative_to(ROOT), text) for p, text in reports]
    if errors:
        parts.append("Error lines of the build:\n```\n%s\n```" % "\n".join(errors[:60])[-REPORT_CHARS:])
    parts.append("Last lines of the build%s:\n```\n%s\n```" % (
        " (%d application log lines left out)" % noise if noise else "",
        "\n".join(lines[-25:])[-2500:]))
    if not reports and not errors:
        parts.append("The build printed no error lines. Run the full test command yourself and read "
                     "its whole output, or the reports under `target/surefire-reports/`.")
    return "\n\n".join(parts)


def failed_test_reports(since, limit=3, chars=2000):
    """Surefire/failsafe `.txt` reports written by this test run that record a failure."""
    found = []
    for p in sorted(ROOT.glob("*/*/target/*-reports/*.txt")):
        try:
            if p.stat().st_mtime < since - 1:
                continue
            text = p.read_text(errors="replace")
        except OSError:
            continue
        if "<<< FAILURE!" in text or "<<< ERROR!" in text:
            found.append((p, text.strip()[:chars]))
    return found[:limit]


def outside_note(module, outside):
    if not outside:
        return ""
    return "\n\nChanges outside `%s/` were not kept: %s" % (module, ", ".join("`%s`" % p for p in outside[:15]))


def title_of(t):
    """`<module>: <title> (#n)`, without doubling a scope the issue title already has."""
    title = re.sub(r"^[\w/ -]+:\s*", "", t["title"], count=1)
    return "%s: %s (#%d)" % (t["mod"]["name"], title, t["number"])


def commit_all(t, message):
    git("add", "-A", "--", t["module"])
    if sh(["git", "diff", "--cached", "--quiet"], cwd=ROOT, check=False)[0] != 0:
        git("commit", "-q", "-m", message)


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
    branch = start_branch(t["number"])
    try:
        return work_ticket(board, t, branch)
    finally:
        back_to_main()


def work_ticket(board, t, branch):
    number, module = t["number"], t["module"]
    install(t)
    run = run_agent(prompt_for(t), number)
    print("agent finished (exit %s): %s" % (run.code, run.result[-300:]))
    ok, report, outside = gate(t, title_of(t))
    rounds = 0
    while not ok and rounds < FIX_ROUNDS:
        rounds += 1
        failed = "The runner committed the changes under `%s/`, threw away the rest of the checkout " \
                 "and ran the full test command, which failed:\n\n%s%s" % (module, report, outside_note(module, outside))
        if run.session and not run.stopped and run.context <= FRESH_CONTEXT:
            print("gate failed; correction round %d (resuming the session)" % rounds)
            run = run_agent("The work is not done. %s\n\nFix it, run the full test command again, and reply "
                            "with a `RESULT:` line when it passes." % failed, number, run.session)
        else:
            why = ("it was stopped: %s" % run.stopped) if run.stopped else \
                  ("its context reached %d tokens" % run.context) if run.session else "it left no session"
            print("gate failed; correction round %d in a fresh session (%s)" % (rounds, why))
            run = run_agent(prompt_for(t) + textwrap.dedent("""
                An earlier attempt at this ticket did not pass. Its work is committed on this branch:
                see `git log origin/main..HEAD` and `git diff origin/main...HEAD`. Keep what is right,
                fix what is wrong. Do not reply `RESULT:` until you have seen the full test command
                pass yourself.
                """) + "\n" + failed, number)
        ok, report, outside = gate(t, "%s: fix after gate round %d (#%d)" % (t["mod"]["name"], rounds, number))
    code, result = run.code, run.result

    if not ok:
        sh(["gh", "issue", "comment", str(number), "--repo", REPO, "--body",
            "%s after %d correction round(s).\n\n%s%s\n\nAgent's last words:\n> %s"
            % (FAILED, rounds, report, outside_note(module, outside), result[-600:].replace("\n", "\n> "))])
        board.move(number, "Blocked" if bounces(t) + 1 >= MAX_BOUNCES else "Todo")
        return False

    git("push", "-q", "-u", "origin", branch, "--force-with-lease")
    names = git("diff", "--name-only", "origin/main...HEAD")
    deps = [n for n in names.split() if n.endswith(("package.json", "package-lock.json", "pom.xml"))]
    body = "Refs #%d.\n\nMade by the local-model ticket runner (`tools/kanban`). The module's test command passed " \
           "on this branch before the pull request was opened (%d correction round(s)).\n\n" \
           "Agent's summary:\n> %s\n\n```\n%s\n```\n%s%s" % (
               number, rounds, result[-800:].replace("\n", "\n> "), report,
               ("\n**Dependency files changed: %s**\n" % ", ".join(deps)) if deps else "",
               outside_note(module, outside))
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
    old = OLD_WORKTREES / ("ticket-%d" % number)
    if old.exists():
        git("worktree", "remove", "--force", str(old), check=False)
    git("branch", "-D", "ticket/%d" % number, check=False)


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
    branch = data["headRefName"]
    # A throwaway, read-only checkout of the branch, so the review never switches the main one.
    wt = Path(tempfile.mkdtemp(prefix="kanban-review-%d-" % number))
    git("fetch", "-q", "origin", "main", branch)
    git("worktree", "add", "-q", "--detach", str(wt), "origin/" + branch)
    try:
        _, diff = sh(["git", "diff", "origin/main...HEAD"], cwd=wt)
        if len(diff) > 60000:
            diff = diff[:60000] + "\n[diff cut at 60000 characters; read the files]"
        prompt = REVIEW_PROMPT.format(module=t["module"], number=number, title=t["title"], body=t["body"], diff=diff)
        code, out = sh(["claude", "-p", prompt, "--model", REVIEW_MODEL, "--output-format", "json",
                        "--allowedTools", "Read", "Grep", "Glob", "Bash(git diff:*)", "Bash(git log:*)",
                        "Bash(git show:*)"], cwd=wt, check=False, timeout=AGENT_TIMEOUT, env=review_env())
    finally:
        git("worktree", "remove", "--force", str(wt), check=False)
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
    p = sub.add_parser("log", help="print a ticket's agent runs, one line per step")
    p.add_argument("issue", type=int)
    a = ap.parse_args()

    if a.cmd == "log":
        path = log_path(a.issue)
        if not path.exists():
            sys.exit("no log at %s" % path)
        for line in path.open():
            for text in event_lines(line, live=False):
                print(text)
        return

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
