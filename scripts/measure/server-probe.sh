#!/usr/bin/env bash
# 1층 측정의 서버 쪽 보조(운영 서버에서 sudo로). 출력은 표준 출력으로만 내고, 받는 쪽(측정 PC)이 파일로 남긴다.
#
#   read            두 칸(blue 8081·green 8082)의 /actuator/prometheus에서 정보나루 관련 줄만 뽑는다
#                   - external_budget_used{provider="datanaru"}: 오늘(KST) 나간 정보나루 호출 수(회차 앞뒤 차이가 호출 수의 주 값, R12)
#                   - http_client_requests_seconds_*: 정보나루 호출 시간 히스토그램(외부 원인 판정 D3, 전후 두 날 비교)
#                   예산 값이 동기화 간격(10초)보다 긴 11초 사이에 그대로이고 칸끼리 같을 때까지 다시 읽는다(최대 5번).
#   reset ISBN...   library_availability_snapshot에서 그 ISBN 행만 지운다(운영 DB 변경 — 측정일에 사용자 확인을 받고 쓴다)
#
# 서버에서 부르는 법(저장소 사본에서 꺼내 바로 실행, 작업 트리를 쓰지 않는다):
#   sudo git -C /opt/checkbook/app fetch -q origin && sudo git -C /opt/checkbook/app show origin/main:scripts/measure/server-probe.sh | sudo bash -s -- read
set -euo pipefail

D=/opt/checkbook/current/deploy
compose() {
  docker compose --project-directory "$D" -f "$D/compose.yaml" --env-file /opt/checkbook/.env "$@"
}

# 두 칸의 /actuator/prometheus에서 정보나루 관련 줄만 "<시각> port=<칸> <지표>" 꼴로 낸다
snapshot() {
  local ts found=0 port body lines
  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  for port in 8081 8082; do
    if body="$(curl -fsS --max-time 3 "http://127.0.0.1:${port}/actuator/prometheus" 2>/dev/null)"; then
      found=1
      lines="$(printf '%s\n' "$body" \
        | grep -E '^external_budget_used\{[^}]*provider="datanaru"|^http_client_requests_seconds_(count|sum|max|bucket)\{[^}]*(data4library|bookExist|libSrch)' || true)"
      # 줄이 비면 외부 원인 판정을 못 한다(집계가 "판정 보류"로 둔다). 측정 PC가 바로 알게 표준 오류로 알린다
      if ! grep -q '^external_budget_used' <<<"$lines"; then
        echo "WARN port=${port} external_budget_used(datanaru) 줄 없음" >&2
      fi
      if ! grep -q '^http_client_requests_seconds_count' <<<"$lines"; then
        echo "WARN port=${port} 정보나루 http_client_requests 줄 없음(라벨 가정 확인 필요)" >&2
      fi
      if [ -n "$lines" ]; then sed "s/^/${ts} port=${port} /" <<<"$lines"; fi
    fi
  done
  if [ "$found" = 0 ]; then
    echo "ERROR 두 칸 모두 /actuator/prometheus 응답 없음" >&2
    return 1
  fi
}

cmd="${1:-}"
shift || true

case "$cmd" in
  read)
    # 예산 게이지는 칸마다 10초 간격으로 DB와 따로 맞춘다. 고정된 대기 시간으로는 동기화를 보장할 수 없어서,
    # 동기화 간격보다 길게(11초) 두 번 읽어 정보나루 예산 값이 그대로이고 칸끼리도 같을 때의 값을 낸다(최대 5번 시도)
    prev=""
    for attempt in 1 2 3 4 5; do
      out="$(snapshot)" || exit 1
      budget="$(grep -E ' external_budget_used\{' <<<"$out" | sed -E 's/^[^ ]+ //' | sort || true)"
      ports_agree="$(sed -E 's/^port=[0-9]+ //' <<<"$budget" | sort -u | wc -l)"
      if [ -n "$prev" ] && [ "$budget" = "$prev" ] && [ "$ports_agree" -le 1 ]; then
        printf '%s\n' "$out"
        exit 0
      fi
      prev="$budget"
      if [ "$attempt" -lt 5 ]; then sleep 11; fi
    done
    echo "WARN 예산 값이 5번 읽는 동안 멈추지 않았거나 칸끼리 다름 — 마지막 값을 낸다(측정 밖 호출이 섞였을 수 있음)" >&2
    printf '%s\n' "$out"
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
