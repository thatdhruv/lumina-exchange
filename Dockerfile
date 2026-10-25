# Build -> small non-root runtime. One image serves every role (api, engine, projector);
# the role comes from LUMINA_ROLES at runtime.
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src ./src
RUN ./mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/orderbook-*.jar extract --layers --destination target/extracted

FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -g 1001 -S lumina && adduser -u 1001 -S lumina -G lumina
WORKDIR /app
COPY --from=builder /build/target/extracted/dependencies/ ./
COPY --from=builder /build/target/extracted/spring-boot-loader/ ./
COPY --from=builder /build/target/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/target/extracted/application/ ./
USER lumina

# 8080 REST/SSE, 9090 gRPC, 8081 actuator (health, Prometheus) - keep 8081 off the public network.
EXPOSE 8080 8081 9090

# Generational ZGC keeps pauses short; the engine is allocation-light but not allocation-free.
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar orderbook-0.4.0-SNAPSHOT.jar"]
