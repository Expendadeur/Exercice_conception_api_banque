FROM maven:3.8.5-openjdk-17 AS build
COPY . .
RUN mnvw.cmd clean package -DskipTests || mvn clean package -DskipTests

FROM openjdk:17-jdk-slim
COPY --from=build /target/banque-0.0.1-SNAPSHOT.jar banque.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","banque.jar"]