# syntax=docker/dockerfile:1
# Build context: the REPO ROOT (see modules.yaml), e.g.
#   docker build -f services/world/Dockerfile -t scarcity-world .

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
COPY libs/world-db/pom.xml libs/world-db/
COPY libs/world-seed/pom.xml libs/world-seed/
COPY libs/service-controller/pom.xml libs/service-controller/
COPY services/auth/pom.xml services/auth/
COPY services/world/pom.xml services/world/
COPY services/subscriptions/pom.xml services/subscriptions/
COPY services/orbital/pom.xml services/orbital/
COPY services/microbe/pom.xml services/microbe/
COPY services/diver/pom.xml services/diver/
# The real lifecycle up to package, over the poms alone, resolves exactly what the image builds
# need (dependency:go-offline misses some, e.g. test-scope ones). There is no code yet, so the
# Boot repackage is skipped, and the empty jars it makes are removed.
RUN mvn -B -ntp -q -DskipTests -Dspring-boot.repackage.skip=true package \
 && find . -name target -type d -prune -exec rm -rf {} +
# <<< maven-deps

FROM deps AS build

COPY libs/observability/src libs/observability/src
COPY libs/resource-model/src libs/resource-model/src
COPY libs/world-model/src libs/world-model/src
COPY libs/world-gen/src libs/world-gen/src
COPY libs/world-engine/src libs/world-engine/src
COPY libs/world-db/src libs/world-db/src
COPY libs/world-seed/src libs/world-seed/src
COPY services/world/src services/world/src
RUN mvn -B -ntp -q -pl services/world -am package -DskipTests

FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 scarcity \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin scarcity
WORKDIR /app
COPY --from=build /src/services/world/target/scarcity-world.jar /app/app.jar
# The git sha of this build (CI passes GIT_SHA to every image), put in each new game's seed
# (docs/resources.md, "Games"). After the jar, so a new sha doesn't rebuild it.
ARG GIT_SHA=unknown
ENV WORLD_BUILD_SHA=$GIT_SHA
USER 10001:10001
# 8080: application API. 8081: actuator (health probes, /actuator/prometheus); not publicly routed.
EXPOSE 8080 8081
# JVM flags live here, not in JAVA_TOOL_OPTIONS: the JVM announces JAVA_TOOL_OPTIONS
# with a plain "Picked up JAVA_TOOL_OPTIONS" line on stderr, which breaks the
# one-JSON-object-per-line logs.
# - MaxRAMPercentage=75: size the heap from the container memory limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
