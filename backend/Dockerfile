FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app/backend
RUN apk add --no-cache maven
COPY backend/pom.xml .
RUN mvn dependency:go-offline -q
COPY backend/src ./src
COPY frontend /app/frontend
RUN mvn package -DskipTests -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl && addgroup -S nudge && adduser -S nudge -G nudge
COPY --from=build /app/backend/target/*.jar app.jar
USER nudge
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
