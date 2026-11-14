---
name: Ticket for the local model
about: Small, well-specified work in one module, run by tools/kanban
labels: "agent: local"
---

Module: clients/web

<!-- One module only (a path from modules.yaml). No design decisions, nothing
     cross-module, nothing touching auth or secrets: those go to a module lead.
     The agent is a weak model: name exact files and say exactly what "done" is. -->

What and why, in two or three sentences.

Read first: `clients/web/AGENTS.md`, `docs/<contract>.md` section "…", `<file>`.

Acceptance:
- [ ] a behaviour that can be checked, with the test that shows it
- [ ] what must not change (no dependency added, no file outside … changes)
- [ ] (Java) `mvn verify` passes its lint: no new entries in `config/lint/` baseline files; split methods that go over a limit
