FROM bellsoft/liberica-openjre-alpine:25

RUN addgroup -S -g 10001 app \
    && adduser -S -u 10001 -G app -h /var/lib/queue-worker app

COPY build/install/queue-worker /opt/queue-worker

USER app
WORKDIR /var/lib/queue-worker
EXPOSE 8080

ENTRYPOINT ["/opt/queue-worker/bin/queue-worker"]
