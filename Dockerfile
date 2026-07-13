# Build the jar with Maven, then ship only the JRE + jar so the runtime image
# stays small and free of build tooling.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B          # cache dependencies in their own layer
COPY src src
RUN mvn clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home app
# Glob the version so the copy survives the next pom version bump.
COPY --from=build /app/target/github-repo-scorer-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
