# Multi-stage build: compile with Maven + JDK 21, ship only a JRE + the jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --create-home bitesite
COPY --from=build /app/target/bitesite.jar app.jar
RUN mkdir -p /app/uploads/logos && chown -R bitesite:bitesite /app
USER bitesite
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
