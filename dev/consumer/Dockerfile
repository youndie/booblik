FROM bellsoft/liberica-openjre-alpine:25

RUN addgroup -S -g 10001 app \
    && adduser -S -u 10001 -G app -h /var/lib/consumer app \
    && mkdir -p /var/lib/consumer \
    && chown app:app /var/lib/consumer

COPY build/install/consumer /opt/consumer

USER app
WORKDIR /var/lib/consumer
EXPOSE 8080

ENTRYPOINT ["/opt/consumer/bin/consumer"]
