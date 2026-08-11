FROM bellsoft/liberica-openjre-alpine:25

RUN addgroup -S -g 10001 app \
    && adduser -S -u 10001 -G app -h /var/lib/publisher app \
    && mkdir -p /var/lib/publisher \
    && chown app:app /var/lib/publisher

COPY build/install/publisher /opt/publisher

USER app
WORKDIR /var/lib/publisher
EXPOSE 8080

ENTRYPOINT ["/opt/publisher/bin/publisher"]
