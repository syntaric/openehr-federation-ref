FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:25-jre-alpine
COPY --from=build /build/target/openehr-federation-ref-*.jar /app/federation-gateway.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=10 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/federation-gateway.jar"]
