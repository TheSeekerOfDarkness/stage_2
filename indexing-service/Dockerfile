FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /workspace/target/indexing-service-1.0.0.jar app.jar

ENV PORT=7002
ENV DATALAKE_PATH=/data/datalake
ENV DATAMART_PATH=/data/datamarts/datamart.db

EXPOSE 7002
ENTRYPOINT ["java", "-jar", "app.jar"]

