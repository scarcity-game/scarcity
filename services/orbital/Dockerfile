# syntax=docker/dockerfile:1
# Build context: the REPO ROOT (see modules.yaml), e.g.
#   docker build -f services/orbital/Dockerfile -t scarcity-orbital .

# >>> maven-deps: the shared dependency stage (config/docker/README.md). Source: config/docker/maven-deps.Dockerfile
FROM maven:3.9-eclipse-temurin-25 AS deps
WORKDIR /src
COPY pom.xml ./
COPY libs/observability/pom.xml libs/observability/
COPY libs/resource-model/pom.xml libs/resource-model/
COPY libs/world-model/pom.xml libs/world-model/
COPY libs/world-engine/pom.xml libs/world-engine/
COPY libs/world-gen/pom.xml libs/world-gen/
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
COPY libs/world-model/src libs/world-model/src
COPY libs/service-controller/src libs/service-controller/src
COPY services/orbital/src services/orbital/src
RUN mvn -B -ntp -q -pl services/orbital -am package -DskipTests

FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 scarcity \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin scarcity
WORKDIR /app
COPY --from=build /src/services/orbital/target/scarcity-orbital.jar /app/app.jar
USER 10001:10001
# 8080: listens, serves nothing. 8081: actuator (health probes, /actuator/prometheus); not publicly routed.
EXPOSE 8080 8081
# JVM flags as services/world's Dockerfile (MaxRAMPercentage).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
