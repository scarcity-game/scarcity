This directory marks the repo root for Maven: `${maven.multiModuleProjectDirectory}`
resolves here even when a build runs from a module with `-f ../../pom.xml` (as CI
does). The lint configuration in `config/lint/` is found through it.

`maven.config` holds `-B -ntp` (batch mode, no download progress), so every
run from the repo or a module gets them. Add `-q` to hide the rest; failures
and the Surefire failure summary still print.
