# syntax=docker/dockerfile:1.7

# ----- build stage -----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src

# Copy poms first to cache dependency layer
COPY pom.xml /src/pom.xml
COPY services/transaction-service/pom.xml /src/services/transaction-service/pom.xml

RUN mvn -B -ntp -f /src/services/transaction-service/pom.xml dependency:go-offline -DskipTests || true

# Now copy sources
COPY services/transaction-service/src /src/services/transaction-service/src

RUN mvn -B -ntp -f /src/services/transaction-service/pom.xml -DskipTests package

# ----- runtime stage -----
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=build /src/services/transaction-service/target/transaction-service.jar /app/app.jar

EXPOSE 8080 8081
USER nonroot
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
