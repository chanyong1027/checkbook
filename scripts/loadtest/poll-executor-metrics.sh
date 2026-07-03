#!/usr/bin/env bash
# /actuator/prometheus에서 풀·커넥션 메트릭을 1초 간격 CSV로 수집
# 사용: ./poll-executor-metrics.sh <출력파일>  (Ctrl+C 또는 kill로 종료)
set -u
OUT="${1:-executor-metrics.csv}"
echo "epoch,metric,value" > "$OUT"
while true; do
  ts=$(date +%s)
  # 스크레이프 실패(포화·GC pause)를 결측 행으로 남겨 plot의 직선 보간이 은폐하지 않게 함
  curl -sf --max-time 2 http://localhost:8080/actuator/prometheus > /tmp/.pollscrape.$$ || { echo "$ts,scrape_failed,1" >> "$OUT"; sleep 1; continue; }
  cat /tmp/.pollscrape.$$ \
    | grep -E '^(executor_(active_threads|queued_tasks)|hikaricp_connections_(active|pending))' \
    | while read -r name value; do
        # 라벨 내부의 콤마(예: {name="x",})가 CSV를 깨지 않도록 세미콜론으로 치환
        echo "$ts,${name//,/;},$value" >> "$OUT"
      done
  sleep 1
done
