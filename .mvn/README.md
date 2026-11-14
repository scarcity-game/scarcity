This directory marks the repo root for Maven: `${maven.multiModuleProjectDirectory}`
resolves here even when a build runs from a module with `-f ../../pom.xml` (as CI
does). The lint configuration in `config/lint/` is found through it.
