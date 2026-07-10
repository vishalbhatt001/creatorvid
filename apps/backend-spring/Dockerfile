# Backend (Spring Boot) image. Build context must be the repo root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY apps/backend-spring/pom.xml pom.xml
RUN mvn -q -B dependency:go-offline
COPY apps/backend-spring/src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

# ffmpeg is required to stitch template clips together + generate thumbnails.
RUN apt-get update \
  && apt-get install -y --no-install-recommends ffmpeg \
  && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/backend-*.jar app.jar
EXPOSE 4001
ENTRYPOINT ["java", "-jar", "app.jar"]
