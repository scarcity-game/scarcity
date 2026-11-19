# config/docker: the shared Maven dependency stage

Every Java service's Dockerfile starts with the same stage, `deps`: the
Maven image, every module's `pom.xml`, and a run of the lifecycle up to
`package` over the poms alone, which fills the local repository with what the
image builds need. The service's own `build` stage starts `FROM deps`.

Because the stage is the same in every Dockerfile, BuildKit gives each of them
the same layer. CI's `deps` job (`.github/workflows/ci.yaml`) builds it once,
from `maven-deps.Dockerfile`, into the GitHub Actions cache scope `maven-deps`,
before the image jobs and the acceptance tests' stack build, and they all read
that scope. So when a pom changes, Maven Central is asked once, not once per
image at the same time (which it answered with 429, #209).

## Changing it
- `maven-deps.Dockerfile` is the source: the block between `# >>> maven-deps`
  and `# <<< maven-deps`. Edit it there, then copy it into every Java
  Dockerfile:

  ```sh
  config/docker/maven-deps.py --write
  ```

- **A new Java module** adds its `COPY <module>/pom.xml <module>/` line to
  the source block (Maven needs every module of the root pom to read the
  reactor, even with `-pl`/`-am`), then `--write`. A new Java service's
  Dockerfile starts with an empty pair of marker lines, which `--write` fills.
- `config/docker/maven-deps.py` with no arguments is the check that CI's
  `plan` job runs. It fails if a Dockerfile's block differs from the source,
  or if the source doesn't COPY exactly the root pom's `<modules>`.

## Why not `dependency:go-offline`
It misses some of what `package` resolves (test-scope dependencies such as
byte-buddy, and plugins' own dependencies), so the package step still went to
Central. The lifecycle run resolves exactly those. It skips the Boot repackage
(there is no main class yet) and deletes the empty `target/` directories it
makes. To check that a change still covers everything, package offline from
the stage:

```sh
docker buildx build --load -t scarcity-maven-deps:test \
  -f config/docker/maven-deps.Dockerfile .
```

then build a throwaway Dockerfile `FROM scarcity-maven-deps:test` that copies
`libs` and `services` and runs `mvn -o -pl services/<name> -am package
-DskipTests` for each service, with `--network=none`.

## Limits
- The base image is a tag (`maven:3.9-eclipse-temurin-25`). If it moves
  between the deps job and an image job, that image misses the shared layer
  and downloads on its own, as before.

## The same problem in the test jobs
The Java test jobs run Maven on the runner, not in Docker. CI's `maven-cache`
job (`.github/workflows/ci.yaml`) fills `~/.m2/repository` once, keyed on every
`pom.xml` and `.mvn/**`, by running `verify` over the whole reactor without
tests (so lint, enforcer and surefire resolve) plus `libs/world-model`'s tests
(which resolve surefire's JUnit provider). The test jobs only restore that
cache and never save one, so a job that needs less can't leave a partial cache
behind (#244).
