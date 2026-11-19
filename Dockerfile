# SmartAttend container image (Java 21 + Spring Boot). Face models are downloaded at build time.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --user-group --create-home smartattend
WORKDIR /app
COPY scripts/download-models.sh /app/scripts/
RUN bash /app/scripts/download-models.sh /app/models
COPY --from=build /src/target/smartattend-1.0.0.jar /app/app.jar
COPY scripts/docker-entrypoint.sh /app/entrypoint.sh
RUN chmod 755 /app/entrypoint.sh && chown -R smartattend /app/models
ENV SMARTATTEND_MODELS_DIR=/app/models SMARTATTEND_ALLOW_DEV_KEYS=false SMARTATTEND_COOKIE_SECURE=true \
    FORWARD_HEADERS_STRATEGY=framework JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
