#!/usr/bin/env bash
set -u
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE \
  http://localhost:8089/__admin/mappings/fa010e00-0000-4000-8000-000000000001)
case "$CODE" in
  200) echo "지연 주입 해제됨 (기본 스텁으로 복귀)" ;;
  404) echo "해제할 지연 스텁 없음 — 이미 기본 상태 (HTTP 404)" ;;
  *)   echo "해제 실패: HTTP $CODE — WireMock(8089) 기동 여부 확인"; exit 1 ;;
esac
