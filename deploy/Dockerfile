# Build the jar first:  ./gradlew :server:shadowJar
# Then, from the repository root:  docker build -f deploy/Dockerfile -t lendger-server .
FROM eclipse-temurin:17-jre-alpine

# A fixed, unprivileged user. The folders exist and belong to it so a fresh named volume inherits that ownership.
RUN addgroup -S -g 10001 lendger \
 && adduser -S -u 10001 -G lendger lendger \
 && mkdir -p /data /backups \
 && chown lendger:lendger /data /backups

WORKDIR /app
COPY server/build/libs/lendger-server.jar /app/lendger-server.jar
USER lendger

ENV LENDGER_DATA_DIR=/data \
    LENDGER_BACKUP_DIR=/backups \
    LENDGER_BIND=0.0.0.0 \
    LENDGER_PORT=8080

VOLUME ["/data", "/backups"]
EXPOSE 8080

# Busybox wget is in the Alpine base image; /healthz answers "ok" while the process is up.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1

# 256 MiB of heap is enough for a circle of 300 members (docs/operator/requirements.md); the process then uses about 400 MiB.
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app/lendger-server.jar"]
CMD ["serve"]
