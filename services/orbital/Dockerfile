# syntax=docker/dockerfile:1
# Build context: the REPO ROOT (see modules.yaml), e.g.
#   docker build -f services/orbital/Dockerfile -t scarcity-orbital .

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src

# POMs first so the dependency layer is cached until a POM changes.
# Maven needs EVERY module listed in the root pom's <modules> to be present to
# build the reactor, even with -pl/-am, so each new Java module adds a COPY of
# its pom.xml here. Only modules orbital depends on also need their src copied.
COPY pom.xml ./
COPY libs/observability/pom.xml libs/observability/
COPY libs/resource-model/pom.xml libs/resource-model/
COPY libs/world-model/pom.xml libs/world-model/
COPY libs/world-engine/pom.xml libs/world-engine/
COPY libs/world-db/pom.xml libs/world-db/
COPY libs/world-seed/pom.xml libs/world-seed/
COPY libs/service-controller/pom.xml libs/service-controller/
COPY services/auth/pom.xml services/auth/
COPY services/world/pom.xml services/world/
COPY services/subscriptions/pom.xml services/subscriptions/
COPY services/orbital/pom.xml services/orbital/
COPY services/microbe/pom.xml services/microbe/
COPY services/diver/pom.xml services/diver/
# -DskipTests here too: it switches off the world-it profile (orbital's pom), whose test
# dependency on world would otherwise pull world into the build.
RUN mvn -B -ntp -q -pl services/orbital -am -DskipTests dependency:go-offline

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
# JVM flags as services/world's Dockerfile (MaxRAMPercentage; the OTel Unsafe warning).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "--sun-misc-unsafe-memory-access=allow", "-jar", "/app/app.jar"]
