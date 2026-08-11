FROM bellsoft/liberica-openjre-alpine:25

LABEL org.opencontainers.image.title="booblik" \
      org.opencontainers.image.description="A message broker: append-only log, one process, no cluster" \
      org.opencontainers.image.licenses="Apache-2.0"

RUN addgroup -S -g 10001 booblik \
    && adduser -S -u 10001 -G booblik -h /var/lib/booblik booblik \
    && mkdir -p /var/lib/booblik \
    && chown booblik:booblik /var/lib/booblik

COPY booblik-app/build/install/booblik-app /opt/booblik

ENV BOOBLIK_DATA_DIR=/var/lib/booblik \
    BOOBLIK_PORT=9092

USER booblik
WORKDIR /var/lib/booblik
VOLUME ["/var/lib/booblik"]
EXPOSE 9092

HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD ["/opt/booblik/bin/booblik-health", "127.0.0.1", "9092"]

ENTRYPOINT ["/opt/booblik/bin/booblik-app"]
