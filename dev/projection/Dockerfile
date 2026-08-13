FROM bellsoft/liberica-openjre-alpine:25

RUN addgroup -S -g 10001 app \
    && adduser -S -u 10001 -G app -h /var/lib/projection app

COPY build/install/projection /opt/projection

USER app
WORKDIR /var/lib/projection
EXPOSE 8080

# No volume, and that is the point: this service stores nothing. Its state is a function of the log
# and is rebuilt by replaying it.
ENTRYPOINT ["/opt/projection/bin/projection"]
