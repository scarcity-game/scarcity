# Java lint

Checkstyle and PMD run on every Java module's **main** sources in `verify`
(so in CI's test command), configured once in the root `pom.xml`. A violation
fails the build. Docker builds run `package` and don't lint. Introduced in #105.

| file | what |
|---|---|
| `checkstyle.xml` | limits: cyclomatic complexity 10 (a switch counts once), NPath 200, method length 60 (blank lines not counted), parameters 7, if nesting 2, for nesting 2, boolean operators 3 per expression, file length 500; plus unused/star imports, empty catch, switch default, equals/hashCode, a newline (LF) at the end of the file |
| `pmd-ruleset.xml` | cognitive complexity 15, GodClass, ExcessiveImports, if nesting (depth 3), CloseResource, unused private methods and locals |
| `checkstyle-suppressions.xml` | baseline: the files and checks that failed on 2026-11-13 |
| `pmd-baseline.properties` | baseline: `class=Rule,Rule` that failed on 2026-11-13 |

## The baseline is a ratchet
- Delete an entry when the code it covers is fixed (or deleted). Never add
  one without the human's OK. New modules have none.
- An entry covers a whole file (Checkstyle) or class (PMD) for that check, so
  inside a baselined file a new violation of the same check slips through.
  Review catches those; the entries go away as the files are split (for
  world, with #103).
- Tests aren't linted (long scenario tests are fine).

## Run it
From a module directory, like CI:

```bash
mvn -B -q -f ../../pom.xml -pl <module> -am verify -DskipTests
```

Only the lint, all modules, from the root:

```bash
mvn -B -q checkstyle:check@lint-checkstyle pmd:check@lint-pmd
```

Reports land in `<module>/target/checkstyle-result.xml` and `target/pmd.xml`.
To look at every violation without failing (when regenerating the baseline),
add `-Dlint.failOnViolation=false`.

`.mvn/` at the repo root makes `${maven.multiModuleProjectDirectory}` the
root even when Maven runs from a module with `-f ../../pom.xml`. That is how
the modules find this directory.
