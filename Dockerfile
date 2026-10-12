FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /build/target/escala-medica.jar .
# /app/data is the Railway Volume (SQLite database and JSON data)
RUN mkdir -p /app/data
EXPOSE 8080
CMD ["java", "-jar", "escala-medica.jar"]
