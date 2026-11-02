FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY order-service/pom.xml order-service/maven-settings.xml ./
COPY order-service/src ./src
COPY catalog /catalog
RUN mvn -s maven-settings.xml -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /data && chown 65532:65532 /data
COPY --from=build /src/target/order-service-1.0.0.jar app.jar
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
