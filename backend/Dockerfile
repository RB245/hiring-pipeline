# Gradle comes from the image rather than from the wrapper. The wrapper resolves its
# distribution through a redirect to GitHub releases, which is not reachable from every
# build network, and a container that cannot download Gradle cannot build at all.
# Keep this tag in step with gradle/wrapper/gradle-wrapper.properties; CI still runs
# ./gradlew, so the wrapper remains the version of record for everything except this.
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /app
# Dependencies resolve in their own layer so that editing a source file does not
# re-download the world on every `docker compose up --build`.
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle --no-daemon dependencies
COPY src src
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
