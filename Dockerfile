# Build with Maven, run on a small JRE. Sized for Render's free instance (512 MB RAM, 0.1 CPU).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/target/financial-analysis.jar app.jar
COPY eval eval
USER app
ENV PORT=7860
EXPOSE 7860
# Small heap and serial GC for 512 MB; C1-only JIT starts faster on a tenth of a CPU.
CMD ["java", "-XX:MaxRAMPercentage=50", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss512k", \
     "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar", "--serve"]
