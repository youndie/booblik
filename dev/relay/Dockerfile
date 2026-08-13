FROM bellsoft/liberica-openjre-alpine:25

RUN addgroup -S -g 10001 app \
    && adduser -S -u 10001 -G app -h /var/lib/relay app \
    && mkdir -p /var/lib/relay \
    && chown app:app /var/lib/relay

COPY build/install/relay /opt/relay

USER app
WORKDIR /var/lib/relay
EXPOSE 8080

ENTRYPOINT ["/opt/relay/bin/relay"]
