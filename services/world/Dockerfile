# syntax=docker/dockerfile:1
# Build context: the REPO ROOT (see modules.yaml), e.g.
#   docker build -f services/world/Dockerfile -t scarcity-world .

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src

# POMs first so the dependency layer is cached until a POM changes.
# Maven needs EVERY module listed in the root pom's <modules> to be present to
# build the reactor, even with -pl/-am, so each new Java module adds a COPY of
# its pom.xml here. Only modules world depends on also need their src copied.
COPY pom.xml ./
COPY libs/resource-model/pom.xml libs/resource-model/
COPY services/auth/pom.xml services/auth/
COPY services/world/pom.xml services/world/
RUN mvn -B -q -pl services/world -am dependency:go-offline

COPY libs/resource-model/src libs/resource-model/src
COPY services/world/src services/world/src
RUN mvn -B -pl services/world -am package -DskipTests

FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 scarcity \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin scarcity
WORKDIR /app
COPY --from=build /src/services/world/target/scarcity-world.jar /app/app.jar
USER 10001:10001
# 8080: application API. 8081: actuator (health probes, /actuator/prometheus); not publicly routed.
EXPOSE 8080 8081
# JVM flags live here, not in JAVA_TOOL_OPTIONS: the JVM announces JAVA_TOOL_OPTIONS
# with a plain "Picked up JAVA_TOOL_OPTIONS" line on stderr, which breaks the
# one-JSON-object-per-line logs.
# - MaxRAMPercentage=75: size the heap from the container memory limit.
# - sun-misc-unsafe-memory-access=allow: the OTel SDK's shaded JCTools uses sun.misc.Unsafe,
#   and JDK 25 would print a 4-line warning to stderr at startup. "allow" keeps the
#   pre-JDK-23 behaviour without the warning. Drop it once OTel stops using Unsafe.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "--sun-misc-unsafe-memory-access=allow", "-jar", "/app/app.jar"]
