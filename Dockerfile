# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
# sharing=locked (sur les mounts /root/.m2 ci-dessous) : le dev compose (pivot-core/compose.yml)
# build ce module EN PARALLÈLE des autres services Java, qui partagent ce même cache BuildKit
# /root/.m2 (le Maven Wrapper y télécharge la distribution Maven). Sans verrou, plusieurs `mvnw`
# concurrents corrompent le zip partagé ("zip END header not found"). Sans effet en CI (cache
# non partagé entre runners).
# fr.pivot:pivot-core-starter (GitHub Packages, repo pivot-core) exige une
# authentification même en lecture — GITHUB_ACTOR/GITHUB_TOKEN injectés comme secrets
# BuildKit (jamais dans les layers ni le build cache) et lus par .mvn/settings.xml
# (server-id "pivot-core-packages", voir ce fichier). Même convention que
# pivot-collaboratif-core (EN08.3).
RUN --mount=type=secret,id=github_actor,env=GITHUB_ACTOR \
    --mount=type=secret,id=github_token,env=GITHUB_TOKEN \
    --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw dependency:go-offline -B -q
COPY src/ src/
RUN --mount=type=secret,id=github_actor,env=GITHUB_ACTOR \
    --mount=type=secret,id=github_token,env=GITHUB_TOKEN \
    --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw package -DskipTests -B -q

# Runtime Alpine : surface OS minimale, CVE réduits. Builder jeté à la fin.
FROM eclipse-temurin:25-jre-alpine
RUN apk upgrade --no-cache
WORKDIR /app
RUN addgroup -S pivot && adduser -S -G pivot pivot
COPY --from=builder /workspace/target/*.jar app.jar
USER pivot
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
