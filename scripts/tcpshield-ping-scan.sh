#!/usr/bin/env bash
set -euo pipefail

SUBNET_PREFIX="${SUBNET_PREFIX:-50.114.4}"
PINGS_PER_HOST="${PINGS_PER_HOST:-5}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-1}"
DELAY_SECONDS="${DELAY_SECONDS:-0.5}"
OUTPUT_CSV="${OUTPUT_CSV:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/PingResults_${SUBNET_PREFIX}.csv}"

tmp="$(mktemp)"
trap 'rm -f "$tmp" "$tmp.fping" "$tmp.sorted"' EXIT

echo "IP,AvgPingMs,Samples" > "$tmp"

if command -v fping >/dev/null 2>&1; then
    ips="$(printf "%s.%s\n" "$SUBNET_PREFIX" $(seq 1 254))"
    set +e
    printf "%s" "$ips" | fping -C "$PINGS_PER_HOST" -q -r 0 -t "$((TIMEOUT_SECONDS * 1000))" > /dev/null 2> "$tmp.fping"
    set -e

    awk -v prefix="$SUBNET_PREFIX" '
        $0 ~ "^" prefix "\\." {
            ip=$1
            sub(/:$/, "", ip)
            count=0
            sum=0
            for (i=2; i<=NF; i++) {
                if ($i ~ /^[0-9]+(\.[0-9]+)?$/) {
                    sum += $i
                    count++
                }
            }
            if (count > 0) {
                printf "%s,%.3f,%d\n", ip, sum / count, count
            }
        }
    ' "$tmp.fping" >> "$tmp"
else
    for i in $(seq 1 254); do
        ip="${SUBNET_PREFIX}.${i}"
        echo "Pinging ${ip} ..."
        output="$(ping -c "$PINGS_PER_HOST" -W "$TIMEOUT_SECONDS" "$ip" 2>/dev/null || true)"
        avg="$(printf "%s\n" "$output" | awk -F'/' '/rtt|round-trip/ { print $5 }')"

        if [[ -n "${avg}" ]]; then
            samples="$(printf "%s\n" "$output" | awk -F',' '/packets transmitted/ { gsub(/[^0-9]/, "", $2); print $2 }')"
            printf "%s,%.3f,%s\n" "$ip" "$avg" "${samples:-$PINGS_PER_HOST}" >> "$tmp"
            echo "${ip} -> Avg: ${avg} ms"
        else
            echo "${ip} -> Unreachable"
        fi

        sleep "$DELAY_SECONDS"
    done
fi

{
    head -n 1 "$tmp"
    tail -n +2 "$tmp" | sort -t, -k2,2n
} > "$tmp.sorted"

mv "$tmp.sorted" "$OUTPUT_CSV"

echo
echo "--- TOP 20 RESULTS ---"
if command -v column >/dev/null 2>&1; then
    set +o pipefail
    column -s, -t "$OUTPUT_CSV" | head -21
    set -o pipefail
else
    head -21 "$OUTPUT_CSV"
fi

echo
echo "Saved results to: $OUTPUT_CSV"
