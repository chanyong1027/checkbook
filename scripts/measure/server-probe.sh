#!/usr/bin/env bash
# 1층 측정의 서버 쪽 보조(운영 서버에서 sudo로). 출력은 표준 출력으로만 내고, 받는 쪽(측정 PC)이 파일로 남긴다.
#
#   read            두 칸(blue 8081·green 8082)의 /actuator/prometheus에서 정보나루 관련 줄만 뽑는다
#                   - external_budget_used{provider="datanaru"}: 오늘(KST) 나간 정보나루 호출 수(회차 앞뒤 차이가 호출 수의 주 값, R12)
#                   - http_client_requests_seconds_*: 정보나루 호출 시간 히스토그램(외부 원인 판정 D3, 전후 두 날 비교)
#                   떠 있는 칸이 모두 응답하고, 예산 값이 11초(동기화 간격 10초보다 길게) 사이 그대로이며 칸끼리 같고,
#                   동기화 실패 카운터가 늘지 않았으며, DB 값이 모든 칸 게이지와 같을 때만 값을 낸다(최대 5번, 안 되면 실패).
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
        | grep -E '^external_budget_used\{[^}]*provider="datanaru"|^external_budget_sync_failures_total|^http_client_requests_seconds_(count|sum|max|bucket)\{[^}]*(data4library|bookExist|libSrch)' || true)"
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

# 정보나루 오늘(KST) 사용량의 DB 값(정수). 행이 없으면 0
db_datanaru_used() {
  local sql="SELECT COALESCE((SELECT used FROM external_call_budget WHERE budget_key = 'DATANARU' AND usage_date = (now() AT TIME ZONE 'Asia/Seoul')::date), 0);"
  compose exec -T -e SQL="$sql" postgres sh -c 'psql -U "$POSTGRES_USER" -d checkbook -tA -v ON_ERROR_STOP=1 -c "$SQL"' \
    | tr -d '[:space:]'
}

cmd="${1:-}"
shift || true

case "$cmd" in
  read)
    # 예산 게이지는 칸마다 10초 간격으로 DB와 따로 맞춘다. 고정된 대기로는 동기화를 보장할 수 없어서 아래가 모두 맞을 때의
    # 값만 낸다(11초 간격으로 다시 읽기, 최대 5번). 끝내 안 맞으면 값을 내지 않고 실패한다(측정 PC는 잠시 뒤 다시 읽는다).
    #   - 떠 있는 앱 칸(docker ps)이 모두 응답하고 정보나루 예산 줄이 있다
    #   - 정보나루 예산 값이 직전 읽기와 같고 칸끼리도 같다(동기화 간격보다 긴 동안 그대로)
    #   - 동기화 실패 카운터가 직전 읽기와 같다
    #   - DB(external_call_budget, DATANARU, 오늘 KST)의 값이 떠 있는 모든 칸의 게이지와 같다. 게이지는 "그 칸이 메모리로 센 것 +
    #     다른 칸이 DB에 쓴 합계"라, 안 쓴 증가분이 있으면 게이지가 DB보다 크고 남이 쓴 것을 아직 안 읽었으면 작다.
    #     같으면 모든 칸의 쓰기와 읽기가 끝난 것이다(실패 카운터만으로는 진행 중인 DB 작업을 못 가른다)
    expected_ports=""
    running="$(docker ps --format '{{.Names}}' 2>/dev/null || true)"
    grep -q '^checkbook-app-blue-1$' <<<"$running" && expected_ports="$expected_ports 8081"
    grep -q '^checkbook-app-green-1$' <<<"$running" && expected_ports="$expected_ports 8082"
    if [ -z "$expected_ports" ]; then
      echo "ERROR 떠 있는 앱 칸을 찾지 못함(docker ps)" >&2
      exit 1
    fi
    prev_budget=""
    prev_fail=""
    for attempt in 1 2 3 4 5; do
      out="$(snapshot)" || out=""
      budget="$(grep -E ' external_budget_used\{' <<<"$out" | sed -E 's/^[^ ]+ //' | sort || true)"
      fail="$(grep -E ' external_budget_sync_failures_total' <<<"$out" | sed -E 's/^[^ ]+ //' | sort || true)"
      all_up=1
      for port in $expected_ports; do
        grep -q "^port=${port} external_budget_used" <<<"$budget" || all_up=0
        grep -q "^port=${port} external_budget_sync_failures_total" <<<"$fail" || all_up=0
      done
      ports_agree="$(sed -E 's/^port=[0-9]+ //' <<<"$budget" | sort -u | wc -l)"
      db_used="$(db_datanaru_used || true)"
      db_agree=1
      if ! [[ "$db_used" =~ ^[0-9]+$ ]]; then
        db_agree=0
      else
        while read -r _ _ value; do
          [ -z "${value:-}" ] && continue
          [ "${value%.*}" = "$db_used" ] || db_agree=0
        done <<<"$(sed -E 's/^(port=[0-9]+) [^ ]+ /\1 x /' <<<"$budget")"
      fi
      if [ "$all_up" = 1 ] && [ "$db_agree" = 1 ] && [ -n "$prev_budget" ] && [ "$budget" = "$prev_budget" ] \
          && [ "$fail" = "$prev_fail" ] && [ "$ports_agree" -le 1 ]; then
        printf '%s\n' "$out"
        echo "# db external_call_budget DATANARU used=${db_used}"
        exit 0
      fi
      prev_budget="$budget"
      prev_fail="$fail"
      if [ "$attempt" -lt 5 ]; then sleep 11; fi
    done
    echo "ERROR 동기화를 확인하지 못함(떠 있는 칸:${expected_ports}, DB ${db_used:-없음}) — 칸 무응답·예산 값 변동·칸끼리 다름·DB와 다름·동기화 실패 증가 중 하나. 잠시 뒤 다시 읽는다" >&2
    exit 1
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
