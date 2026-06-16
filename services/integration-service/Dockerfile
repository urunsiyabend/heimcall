# syntax=docker/dockerfile:1
# Build context: this service directory. Requires the layered bootJar prebuilt:
#   ./gradlew :services:integration-service:bootJar
# Then:  docker build -t heimcall/integration-service:0.0.1-SNAPSHOT services/integration-service

# Stage 1 — split the layered bootJar so dependency layers cache across rebuilds.
FROM eclipse-temurin:21-jre AS extract
WORKDIR /app
ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# Stage 2 — runtime image, non-root.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd -r heimcall && useradd -r -g heimcall -u 1001 heimcall
COPY --from=extract /app/dependencies/ ./
COPY --from=extract /app/spring-boot-loader/ ./
COPY --from=extract /app/snapshot-dependencies/ ./
COPY --from=extract /app/application/ ./
USER 1001
EXPOSE 8081
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
