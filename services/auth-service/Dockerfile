# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY pom.xml /src/pom.xml
COPY services/auth-service/pom.xml /src/services/auth-service/pom.xml
RUN mvn -B -ntp -f /src/services/auth-service/pom.xml dependency:go-offline -DskipTests || true

COPY services/auth-service/src /src/services/auth-service/src
RUN mvn -B -ntp -f /src/services/auth-service/pom.xml -DskipTests package

FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=build /src/services/auth-service/target/auth-service.jar /app/app.jar

EXPOSE 8080 8081
USER nonroot
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
