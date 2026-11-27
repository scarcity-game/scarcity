# syntax=docker/dockerfile:1
# >>> maven-deps: the shared dependency stage (config/docker/README.md). Source: config/docker/maven-deps.Dockerfile
FROM maven:3.9-eclipse-temurin-25 AS deps
WORKDIR /src
COPY pom.xml ./
COPY libs/observability/pom.xml libs/observability/
COPY libs/resource-model/pom.xml libs/resource-model/
COPY libs/world-model/pom.xml libs/world-model/
COPY libs/world-engine/pom.xml libs/world-engine/
COPY libs/world-gen/pom.xml libs/world-gen/
COPY libs/pathfinding/pom.xml libs/pathfinding/
COPY libs/combat/pom.xml libs/combat/
COPY libs/world-db/pom.xml libs/world-db/
COPY libs/world-seed/pom.xml libs/world-seed/
COPY libs/service-controller/pom.xml libs/service-controller/
COPY services/auth/pom.xml services/auth/
COPY services/world/pom.xml services/world/
COPY services/subscriptions/pom.xml services/subscriptions/
COPY services/orbital/pom.xml services/orbital/
COPY services/microbe/pom.xml services/microbe/
COPY services/brawler/pom.xml services/brawler/
COPY services/diver/pom.xml services/diver/
# The real lifecycle up to package, over the poms alone, resolves exactly what the image builds
# need (dependency:go-offline misses some, e.g. test-scope ones). There is no code yet, so the
# Boot repackage is skipped, and the empty jars it makes are removed.
RUN mvn -B -ntp -q -DskipTests -Dspring-boot.repackage.skip=true package \
 && find . -name target -type d -prune -exec rm -rf {} +
# <<< maven-deps
