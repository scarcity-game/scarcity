# syntax=docker/dockerfile:1
# Build context: the REPO ROOT (see modules.yaml), e.g.
#   docker build -f services/auth/Dockerfile -t scarcity-auth .

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src

# POMs first so the dependency layer is cached until a POM changes.
# NOTE: when the root pom lists more modules, copy their pom.xml files here too
# (Maven needs every reactor module's POM to resolve -pl/-am).
COPY pom.xml ./
COPY services/auth/pom.xml services/auth/
RUN mvn -B -q -pl services/auth -am dependency:go-offline

COPY services/auth/src services/auth/src
RUN mvn -B -pl services/auth -am package -DskipTests

FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 scarcity \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin scarcity
WORKDIR /app
COPY --from=build /src/services/auth/target/scarcity-auth.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
