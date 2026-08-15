# Étape 1 : Compilation avec Maven et Java 17
FROM maven:3.9.16-eclipse-temurin-17 AS build
COPY . .
RUN mvn clean package -DskipTests

# Étape 2 : Exécution de l'API Banque avec l'image officielle moderne Temurin
FROM eclipse-temurin:17-jre-jammy
COPY --from=build /target/banque-0.0.1-SNAPSHOT.jar banque.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","banque.jar"]