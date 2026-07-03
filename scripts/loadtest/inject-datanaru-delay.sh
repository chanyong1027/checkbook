#!/usr/bin/env bash
# datanaru bookExist에 지연 주입. 기본 1900ms(= read-timeout 2000ms 직전, 실패 없는 최악 지연)
# 서킷브레이커가 개입하지 못하는 "느린 성공" — 무제한 큐 적체를 최대로 드러낸다.
# POST /__admin/mappings/import는 ID 기준 upsert라 재실행에 멱등 (실증 확인).
# PUT /__admin/mappings/{id}는 미존재 스텁에 404를 반환해 생성이 불가하므로 쓰면 안 됨.
# 사용: ./inject-datanaru-delay.sh [지연ms]
set -u
DELAY_MS="${1:-1900}"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
  http://localhost:8089/__admin/mappings/import \
  -H "Content-Type: application/json" -d '{
  "mappings": [
    {
      "id": "fa010e00-0000-4000-8000-000000000001",
      "priority": 1,
      "request": { "method": "GET", "urlPath": "/api/bookExist" },
      "response": {
        "status": 200,
        "headers": { "Content-Type": "application/json" },
        "body": "{\"response\":{\"result\":{\"hasBook\":\"Y\",\"loanAvailable\":\"Y\"}}}",
        "fixedDelayMilliseconds": '"$DELAY_MS"'
      }
    }
  ]
}')
if [ "$CODE" = "200" ]; then
  echo "datanaru bookExist 지연 ${DELAY_MS}ms 주입됨 (HTTP $CODE)"
else
  echo "주입 실패: HTTP $CODE — WireMock(8089) 기동 여부 확인"
  exit 1
fi
