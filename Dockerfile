FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Khống chế trần RAM 1GB và bật chế độ compile nhẹ để tránh crash JVM
ENV MAVEN_OPTS="-Xmx1024m -XX:+TieredCompilation -XX:TieredStopAtLevel=1"

COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# Giới hạn RAM runtime cho Spring Boot không ăn quá 512MB
ENTRYPOINT ["java", "-Xmx512m", "-jar", "app.jar"]
