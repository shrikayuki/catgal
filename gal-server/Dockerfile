FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY gal-common/pom.xml gal-common/pom.xml
COPY gal-server/pom.xml gal-server/pom.xml
COPY gal-common/src gal-common/src
COPY gal-server/src gal-server/src
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -ntp package
COPY deploy/linux/HealthCheck.java /tmp/HealthCheck.java
RUN javac -d /health /tmp/HealthCheck.java

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --gid 10001 catgal && useradd --uid 10001 --gid catgal --no-create-home catgal
WORKDIR /app
COPY --from=build --chown=catgal:catgal /build/gal-server/target/gal-server-*.jar app.jar
COPY --from=build /health /opt/health
USER catgal
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
