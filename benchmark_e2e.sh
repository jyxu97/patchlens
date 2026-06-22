#!/bin/bash
# End-to-end latency benchmark: webhook received → SSE COMPLETED event
#
# Measures the time from sending a webhook to receiving the COMPLETED
# status event over SSE. Runs N iterations and prints avg/min/max.
#
# Usage: ./benchmark_e2e.sh [iterations]
# Example: ./benchmark_e2e.sh 5

set -euo pipefail

BASE_URL="http://localhost:8080"
ITERATIONS="${1:-5}"
PR_URL="${2:-https://github.com/spring-projects/spring-boot/pull/1}"

# Parse owner/repo/number from PR URL
# Expected format: https://github.com/{owner}/{repo}/pull/{number}
OWNER=$(echo "$PR_URL" | cut -d'/' -f4)
REPO=$(echo "$PR_URL"  | cut -d'/' -f5)
PR_NUM=$(echo "$PR_URL" | cut -d'/' -f7)

echo "PR: $OWNER/$REPO#$PR_NUM"

WEBHOOK_PAYLOAD="{
  \"action\": \"opened\",
  \"pull_request\": {
    \"number\": $PR_NUM,
    \"html_url\": \"$PR_URL\",
    \"title\": \"benchmark test\"
  },
  \"repository\": {
    \"name\": \"$REPO\",
    \"owner\": { \"login\": \"$OWNER\" }
  }
}"

echo "Running $ITERATIONS iterations..."
echo "---"

TIMES=()

for i in $(seq 1 "$ITERATIONS"); do
  # 1. Send webhook, record start time (milliseconds, macOS-compatible)
  START=$(python3 -c "import time; print(int(time.time() * 1000))")

  RESPONSE=$(curl -s -X POST "$BASE_URL/api/webhooks/github" \
    -H "Content-Type: application/json" \
    -H "X-GitHub-Event: pull_request" \
    -d "$WEBHOOK_PAYLOAD")

  JOB_ID=$(echo "$RESPONSE" | grep -o '"jobId":"[^"]*"' | cut -d'"' -f4)

  if [ -z "$JOB_ID" ]; then
    echo "[$i] ERROR: could not get jobId. Response: $RESPONSE"
    continue
  fi

  # 2. Subscribe to SSE stream, wait for COMPLETED (or FAILED/DEAD_LETTER)
  TERMINAL=$(curl -sN "$BASE_URL/api/jobs/$JOB_ID/stream" \
    --max-time 120 \
    | grep -m1 -E '"status":"COMPLETED"|"status":"FAILED"|"status":"DEAD_LETTER"')

  END=$(python3 -c "import time; print(int(time.time() * 1000))")

  # 3. Compute elapsed milliseconds
  ELAPSED_MS=$(( END - START ))
  TIMES+=("$ELAPSED_MS")

  STATUS=$(echo "$TERMINAL" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
  echo "[$i] jobId=$JOB_ID  status=$STATUS  elapsed=${ELAPSED_MS}ms"
done

# 4. Summary stats
if [ "${#TIMES[@]}" -eq 0 ]; then
  echo "No successful runs."
  exit 1
fi

TOTAL=0
MIN="${TIMES[0]}"
MAX="${TIMES[0]}"

for T in "${TIMES[@]}"; do
  TOTAL=$((TOTAL + T))
  (( T < MIN )) && MIN=$T
  (( T > MAX )) && MAX=$T
done

AVG=$((TOTAL / ${#TIMES[@]}))

echo "---"
echo "Iterations : ${#TIMES[@]}"
echo "Avg        : ${AVG}ms"
echo "Min        : ${MIN}ms"
echo "Max        : ${MAX}ms"
