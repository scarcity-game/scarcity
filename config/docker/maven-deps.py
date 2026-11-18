#!/usr/bin/env python3
"""Keeps the shared Maven dependency stage the same in every Java Dockerfile.

The stage between the `# >>> maven-deps` and `# <<< maven-deps` lines of
config/docker/maven-deps.Dockerfile is the source. Every Java service's
Dockerfile carries it verbatim, so BuildKit gives each of them the same
dependency layer, which CI's `deps` job builds once (config/docker/README.md).

    config/docker/maven-deps.py           check: exit 1 on any difference
    config/docker/maven-deps.py --write   copy the source block into each Dockerfile

The check also fails if the source block doesn't COPY exactly the poms of the
root pom's <modules>, since Maven needs all of them to read the reactor.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "config/docker/maven-deps.Dockerfile"
BLOCK = re.compile(r"^# >>> maven-deps.*?^# <<< maven-deps\n", re.M | re.S)


def block(text, where):
    found = BLOCK.findall(text)
    if len(found) != 1:
        sys.exit(f"{where}: expected one '# >>> maven-deps' ... '# <<< maven-deps' block, found {len(found)}")
    return found[0]


def java_dockerfiles():
    return sorted(p for p in ROOT.glob("services/*/Dockerfile") if "mvn " in p.read_text())


def pom_errors(source):
    modules = re.findall(r"<module>([^<]+)</module>", (ROOT / "pom.xml").read_text())
    copied = re.findall(r"^COPY (\S+)/pom\.xml \1/$", source, re.M)
    errors = [f"{SOURCE.relative_to(ROOT)}: no COPY of {m}/pom.xml" for m in modules if m not in copied]
    errors += [f"{SOURCE.relative_to(ROOT)}: COPY of {c}/pom.xml, not a module in pom.xml" for c in copied
               if c not in modules]
    return errors


def main():
    write = sys.argv[1:] == ["--write"]
    source = block(SOURCE.read_text(), SOURCE)
    errors = pom_errors(source)
    for path in java_dockerfiles():
        text = path.read_text()
        if block(text, path) == source:
            continue
        if write:
            path.write_text(BLOCK.sub(lambda _: source, text))
            print(f"updated {path.relative_to(ROOT)}")
        else:
            errors.append(f"{path.relative_to(ROOT)}: the maven-deps block differs from {SOURCE.relative_to(ROOT)}"
                          " (run config/docker/maven-deps.py --write)")
    for e in errors:
        print(e, file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
