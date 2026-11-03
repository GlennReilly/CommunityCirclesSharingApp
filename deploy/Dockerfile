# Build the jar first:  ./gradlew :server:shadowJar
# Then, from the repository root:  docker build -f deploy/Dockerfile -t circles-server .
FROM eclipse-temurin:17-jre-alpine

# A fixed, unprivileged user. The folders exist and belong to it so a fresh named volume inherits that ownership.
RUN addgroup -S -g 10001 circles \
 && adduser -S -u 10001 -G circles circles \
 && mkdir -p /data /backups \
 && chown circles:circles /data /backups

WORKDIR /app
COPY server/build/libs/circles-server.jar /app/circles-server.jar
USER circles

ENV CIRCLES_DATA_DIR=/data \
    CIRCLES_BACKUP_DIR=/backups \
    CIRCLES_BIND=0.0.0.0 \
    CIRCLES_PORT=8080

VOLUME ["/data", "/backups"]
EXPOSE 8080

# Busybox wget is in the Alpine base image; /healthz answers "ok" while the process is up.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1

# 256 MiB of heap is enough for a circle of 300 members (docs/operator/requirements.md); the process then uses about 400 MiB.
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app/circles-server.jar"]
CMD ["serve"]
