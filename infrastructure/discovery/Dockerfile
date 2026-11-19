FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY contracts contracts
COPY services/identity services/identity
COPY services/registry services/registry
COPY services/analyzer services/analyzer
COPY infrastructure/discovery infrastructure/discovery
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -ntp -pl infrastructure/discovery -am -DskipTests package

FROM eclipse-temurin:21.0.11_10-jre-jammy
RUN groupadd --system sentinel && useradd --system --gid sentinel --home /app sentinel
WORKDIR /app
COPY --from=build /src/infrastructure/discovery/target/discovery-server-1.0.0-SNAPSHOT.jar /app/service.jar
COPY --chmod=755 infrastructure/discovery/check-health.sh /app/check-health
USER sentinel:sentinel
EXPOSE 8761
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/service.jar"]
