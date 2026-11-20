#!/usr/bin/env bash
# 1층 측정의 서버 쪽 보조(운영 서버에서 sudo로). 출력은 표준 출력으로만 내고, 받는 쪽(측정 PC)이 파일로 남긴다.
#
#   read            두 칸(blue 8081·green 8082)의 /actuator/prometheus에서 정보나루 관련 줄만 뽑는다
#                   - external_budget_used{provider="datanaru"}: 오늘(KST) 나간 정보나루 호출 수(회차 앞뒤 차이가 호출 수의 주 값, R12)
#                   - http_client_requests_seconds_*: 정보나루 호출 시간 히스토그램(외부 원인 판정 D3, 전후 두 날 비교)
#                   DB 동기화가 10초 간격이라 회차가 끝나고 15초 뒤에 읽는다(README).
#   reset ISBN...   library_availability_snapshot에서 그 ISBN 행만 지운다(운영 DB 변경 — 측정일에 사용자 확인을 받고 쓴다)
#
# 서버에서 부르는 법(저장소 사본에서 꺼내 바로 실행, 작업 트리를 쓰지 않는다):
#   sudo git -C /opt/checkbook/app fetch -q origin && sudo git -C /opt/checkbook/app show origin/main:scripts/measure/server-probe.sh | sudo bash -s -- read
set -euo pipefail

D=/opt/checkbook/current/deploy
compose() {
  docker compose --project-directory "$D" -f "$D/compose.yaml" --env-file /opt/checkbook/.env "$@"
}

cmd="${1:-}"
shift || true

case "$cmd" in
  read)
    ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    found=0
    for port in 8081 8082; do
      if body="$(curl -fsS --max-time 3 "http://127.0.0.1:${port}/actuator/prometheus" 2>/dev/null)"; then
        found=1
        printf '%s\n' "$body" \
          | grep -E '^external_budget_used\{[^}]*provider="datanaru"|^http_client_requests_seconds_(count|sum|max|bucket)\{[^}]*(data4library|bookExist|libSrch)' \
          | sed "s/^/${ts} port=${port} /" || true
      fi
    done
    if [ "$found" = 0 ]; then
      echo "ERROR 두 칸 모두 /actuator/prometheus 응답 없음" >&2
      exit 1
    fi
    ;;
  reset)
    if [ "$#" -eq 0 ]; then
      echo "사용법: reset ISBN [ISBN...]" >&2
      exit 2
    fi
    list=""
    for isbn in "$@"; do
      if ! [[ "$isbn" =~ ^[0-9]{13}$ ]]; then
        echo "잘못된 ISBN: $isbn" >&2
        exit 2
      fi
      list="${list:+${list},}'${isbn}'"
    done
    # 값은 위에서 숫자 13자리로 확인했다. 환경 변수로 넘겨 따옴표가 셸을 두 번 지나지 않게 한다
    compose exec -T -e SQL="DELETE FROM library_availability_snapshot WHERE isbn13 IN (${list});" postgres \
      sh -c 'psql -U "$POSTGRES_USER" -d checkbook -v ON_ERROR_STOP=1 -c "$SQL"'
    ;;
  *)
    echo "사용법: read | reset ISBN [ISBN...]" >&2
    exit 2
    ;;
esac
