#!/usr/bin/env bash
#
# The round trip: Kafka -> booblik -> Kafka (M-110).
#
# Records are put into a Kafka topic, carried into booblik by one relay and back out into another
# Kafka topic by the other, and what comes out is compared with what went in. Nothing is read from
# the relays' own logs: they are the thing under test.
#
# Duplicates are **not** a failure. Both directions are at-least-once by construction, so the
# assertion is that every record arrived, and repeats are reported rather than punished.

set -euo pipefail

cd "$(dirname "$0")"

COUNT="${COUNT:-200}"
IN_TOPIC="${IN_TOPIC:-orders}"
OUT_TOPIC="${OUT_TOPIC:-mirrored-back}"
KAFKA=(docker compose exec -T kafka /opt/kafka/bin)

echo "→ producing $COUNT records into Kafka topic $IN_TOPIC"
# Errors are not swallowed. An earlier version sent this to /dev/null, and when the relay came
# back with nothing there was no way to tell a broken relay from records that were never produced.
seq 1 "$COUNT" | sed 's/^/order-/' |
    docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
        --bootstrap-server kafka:9092 --topic "$IN_TOPIC" 2>&1 | grep -vE '^\[[0-9]' || true

PRODUCED=$(docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server kafka:9092 --topic "$IN_TOPIC" 2>/dev/null |
    awk -F: '{sum += $3} END {print sum + 0}')
echo "   Kafka holds $PRODUCED records in $IN_TOPIC"
[ "${PRODUCED:-0}" -ge "$COUNT" ] || { echo "::error:: the records never reached Kafka"; exit 1; }

echo "→ waiting for both relays to carry them"
for _ in $(seq 1 90); do
    IN=$(curl -fsS localhost:8094/stats | python3 -c 'import json,sys; print(json.load(sys.stdin)["relayed"])' 2>/dev/null || echo 0)
    OUT=$(curl -fsS localhost:8095/stats | python3 -c 'import json,sys; print(json.load(sys.stdin)["relayed"])' 2>/dev/null || echo 0)
    [ "${IN:-0}" -ge "$COUNT" ] && [ "${OUT:-0}" -ge "$COUNT" ] && break
    sleep 2
done
echo "   relay-in carried ${IN:-0}, relay-out carried ${OUT:-0}"

echo "→ reading $OUT_TOPIC back out of Kafka"
docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server kafka:9092 --topic "$OUT_TOPIC" \
    --from-beginning --timeout-ms 15000 2>/dev/null |
    COUNT="$COUNT" python3 check.py roundtrip

echo
echo "✓ every record crossed into booblik and came back"
