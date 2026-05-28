# syntax=docker/dockerfile:1.7

# ----- build stage -----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY pom.xml /src/pom.xml
COPY services/gateway/pom.xml /src/services/gateway/pom.xml

RUN mvn -B -ntp -f /src/services/gateway/pom.xml dependency:go-offline -DskipTests || true

COPY services/gateway/src /src/services/gateway/src

RUN mvn -B -ntp -f /src/services/gateway/pom.xml -DskipTests package

# ----- runtime stage -----
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=build /src/services/gateway/target/gateway.jar /app/app.jar

EXPOSE 8080 8081
USER nonroot
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
