FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY . .

RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -DskipTests package


FROM eclipse-temurin:21-jre

WORKDIR /app

RUN mkdir -p /app/data

COPY --from=build /app/target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]