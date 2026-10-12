FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY audit-service/build/libs/*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
