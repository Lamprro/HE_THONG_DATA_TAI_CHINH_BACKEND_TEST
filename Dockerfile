FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

ENV MAVEN_OPTS="-Xmx1024m -XX:+TieredCompilation -XX:TieredStopAtLevel=1"

COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system financial && useradd --system --gid financial financial && chown financial:financial /app
COPY --from=build /app/target/*.jar app.jar
USER financial
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-jar", "app.jar"]
