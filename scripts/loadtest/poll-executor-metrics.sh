#!/usr/bin/env bash
# /actuator/prometheus에서 풀·커넥션 메트릭을 1초 간격 CSV로 수집
# 사용: ./poll-executor-metrics.sh <출력파일>  (Ctrl+C 또는 kill로 종료)
set -u
OUT="${1:-executor-metrics.csv}"
echo "epoch,metric,value" > "$OUT"
while true; do
  ts=$(date +%s)
  curl -sf http://localhost:8080/actuator/prometheus \
    | grep -E '^(executor_(active_threads|queued_tasks)|hikaricp_connections_active)' \
    | while read -r name value; do
        # 라벨 내부의 콤마(예: {name="x",})가 CSV를 깨지 않도록 세미콜론으로 치환
        echo "$ts,${name//,/;},$value" >> "$OUT"
      done
  sleep 1
done
