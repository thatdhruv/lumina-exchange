# Multi-stage production image: builder + non-root runtime with container-aware JVM tuning.
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN apk add --no-cache wget \
    && addgroup -g 1001 -S lumina \
    && adduser -u 1001 -S lumina -G lumina
WORKDIR /app
COPY --from=builder /build/target/orderbook-0.3.0-SNAPSHOT.jar app.jar
RUN chown -R lumina:lumina /app
USER lumina

EXPOSE 8080 9090

ENV JAVA_OPTS="-XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
