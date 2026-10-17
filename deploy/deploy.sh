#!/usr/bin/env bash
# shellcheck disable=SC2317,SC2329  # 되돌리기 함수들은 EXIT·신호 trap에서만 불린다(0.10은 SC2317, 0.11은 SC2329)
# CheckBook 블루·그린 배포 (OCI 서버, root로 실행)
#
#   sudo ./deploy.sh sha256:<64자리>          # CI 요약 화면의 digest
#   sudo ./deploy.sh ghcr.io/chanyong1027/checkbook@sha256:<64자리>
#
# 순서: 이미지 받기 → 쉬는 칸에 새 버전 기동 → 헬스 확인(최대 90초, 2초 간격) → Nginx upstream 교체
#       → nginx -t → reload → Nginx를 거쳐 새 칸이 응답하는지 확인 → 옛 칸 종료.
#       어느 단계에서 실패하거나 중간에 끊겨도(Ctrl-C, SSH 끊김) 새 칸만 지우고 옛 칸과 Nginx 설정을 되돌린다
#       (OCI 이전 결정 카드 2·7). Nginx 설정을 되돌리지 못하면 두 칸을 모두 남기고 손으로 할 일을 출력하고,
#       다음 배포는 upstream 파일과 실제 Nginx가 다시 맞을 때까지 시작하지 않는다.
#       SSH가 끊길 수 있으니 tmux 안에서 돌리는 것을 권한다.
# 지금 서비스 중인 칸은 상태 파일이 아니라 Nginx upstream 파일로 판단한다(실제로 트래픽이 가는 곳이 기준).
# 되돌리기: 이전 digest로 다시 실행한다(성공 메시지 마지막 줄).
set -euo pipefail
# 출력을 받던 파이프(| tee 등)가 끊겨도 SIGPIPE로 죽지 않게 한다 — 쓰기 실패는 log가 무시한다
trap '' PIPE

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/compose.yaml"

IMAGE_REPO="${CHECKBOOK_IMAGE_REPO:-ghcr.io/chanyong1027/checkbook}"
ENV_FILE="${CHECKBOOK_ENV_FILE:-/opt/checkbook/.env}"
STATE_FILE="${CHECKBOOK_STATE_FILE:-/opt/checkbook/deploy-state.env}"
UPSTREAM_FILE="${CHECKBOOK_UPSTREAM_FILE:-/etc/nginx/checkbook/upstream.conf}"
UPSTREAM_HOST="${CHECKBOOK_UPSTREAM_HOST:-127.0.0.1}"
# 누구나 쓰는 /run/lock에 두면 다른 사용자가 잠금을 먼저 잡아 배포를 막을 수 있어 root 전용 폴더에 둔다.
# 래퍼(checkbook-deploy)의 잠금과는 다른 파일이다(같으면 래퍼 아래에서 이 스크립트가 바로 실패한다)
LOCK_FILE="${CHECKBOOK_LOCK_FILE:-/run/checkbook/deploy.lock}"
NGINX_TEST_CMD="${CHECKBOOK_NGINX_TEST_CMD:-nginx -t}"
NGINX_RELOAD_CMD="${CHECKBOOK_NGINX_RELOAD_CMD:-systemctl reload nginx}"
# 서버 안에서 Nginx를 거쳐 API 1건을 부르고 응답 헤더를 찍는 명령(Cloudflare를 거치지 않음).
# 외부 API를 부르지 않고 DB만 읽는 엔드포인트를 쓴다
NGINX_CHECK_CMD="${CHECKBOOK_NGINX_CHECK_CMD:-curl -fsSk --max-time 3 -o /dev/null -D - --resolve checkbook.site:443:127.0.0.1 'https://checkbook.site/api/featured?type=BESTSELLER'}"
# 진행 기록. SSH가 끊겨 화면 출력이 안 돼도 여기에는 남는다
LOG_FILE="${CHECKBOOK_DEPLOY_LOG:-/var/log/checkbook-deploy.log}"
# 교체 뒤 Cloudflare를 거친 확인 1건(경고만)
EXTERNAL_CHECK_URL="${CHECKBOOK_EXTERNAL_CHECK_URL:-https://checkbook.site/api/featured?type=BESTSELLER}"

HEALTH_TIMEOUT_SECONDS=90
HEALTH_INTERVAL_SECONDS=2
NGINX_CHECK_TIMEOUT_SECONDS=15
# reload 뒤 옛 worker가 이미 받은 요청을 옛 칸으로 마저 보내는 시간
DRAIN_SECONDS=5

# 출력이 실패해도(SSH 끊김으로 터미널이 사라진 경우) 스크립트를 멈추지 않는다 — 되돌리기가 먼저다
log() {
  local line
  line="[$(date '+%Y-%m-%d %H:%M:%S')] $*"
  { printf '%s\n' "${line}" >>"${LOG_FILE}"; } 2>/dev/null || true
  printf '%s\n' "${line}" 2>/dev/null || true
}
die() { log "실패: $*"; exit 1; }

# 명령 출력을 화면에 바로 쓰지 않고 log로 넘긴다(출력이 막혀도 명령 자체의 성공·실패만 본다)
run_logged() {
  local out rc=0
  out="$(bash -c "$1" 2>&1)" || rc=$?
  if [[ -n "${out}" ]]; then
    while IFS= read -r line; do log "  ${line}"; done <<<"${out}"
  fi
  return "${rc}"
}

port_of() {
  case "$1" in
    blue) echo 8081 ;;
    green) echo 8082 ;;
    *) die "알 수 없는 칸: $1" ;;
  esac
}

compose() {
  docker compose --project-directory "${SCRIPT_DIR}" -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" "$@"
}

# Nginx를 거쳐 1건 불러 지금 적용된 설정의 칸 이름(X-Checkbook-Slot)을 출력한다. 응답이 없으면 빈 줄.
# 응답 코드는 보지 않는다(헤더는 오류 응답에도 붙어서, 서비스 중인 앱이 죽어 있어도 Nginx가 어디로 보내는지 알 수 있다)
nginx_slot() {
  local headers
  headers="$(bash -c "${NGINX_CHECK_CMD}" 2>/dev/null)" || true
  awk -F': *' 'tolower($1) == "x-checkbook-slot" && !n { sub(/\r$/, "", $2); print $2; n = 1 }' <<<"${headers}"
}

usage() {
  echo "사용법: sudo $0 sha256:<64자리 digest> | ${IMAGE_REPO}@sha256:<64자리 digest>" >&2
  exit 2
}

[[ $# -eq 1 ]] || usage
case "$1" in
  sha256:*) IMAGE="${IMAGE_REPO}@$1" ;;
  *@sha256:*) IMAGE="$1" ;;
  *) usage ;;
esac
# 태그(latest 등)로는 배포하지 않는다. 같은 이름에 다른 이미지가 올라와도 digest가 다르면 뜨지 않게
[[ "${IMAGE}" =~ @sha256:[0-9a-f]{64}$ ]] || die "digest 형식이 아닙니다: ${IMAGE}"
[[ -r "${ENV_FILE}" ]] || die "env 파일을 읽을 수 없습니다: ${ENV_FILE}"

# 동시에 두 배포가 돌면 칸 상태가 꼬인다
# 없을 때만 만든다(이미 있는 폴더 권한을 바꾸지 않게. 로컬 검증에서 /tmp를 주면 /tmp가 700이 된다)
[[ -d "$(dirname "${LOCK_FILE}")" ]] || install -d -m 700 "$(dirname "${LOCK_FILE}")"
exec 9>"${LOCK_FILE}"
flock -n 9 || die "다른 배포가 진행 중입니다(${LOCK_FILE})"

# 상태 파일은 칸별 이미지 기록용이다
BLUE_IMAGE=""
GREEN_IMAGE=""
if [[ -f "${STATE_FILE}" ]]; then
  BLUE_IMAGE="$(sed -n 's/^BLUE_IMAGE=//p' "${STATE_FILE}")"
  GREEN_IMAGE="$(sed -n 's/^GREEN_IMAGE=//p' "${STATE_FILE}")"
fi

# 서비스 중인 칸 = upstream 파일이 가리키는 포트
OLD=none
if [[ -f "${UPSTREAM_FILE}" ]]; then
  live_port="$(sed -n 's/^[[:space:]]*server [^:]*:\([0-9]*\);.*/\1/p' "${UPSTREAM_FILE}" | head -n 1)"
  case "${live_port}" in
    8081) OLD=blue ;;
    8082) OLD=green ;;
    *) die "upstream 파일에서 서비스 중인 칸을 알 수 없습니다: ${UPSTREAM_FILE}" ;;
  esac
fi
case "${OLD}" in
  blue) TARGET=green ;;
  *) TARGET=blue ;;
esac
TARGET_PORT="$(port_of "${TARGET}")"
OLD_IMAGE=""
if [[ "${OLD}" == blue ]]; then OLD_IMAGE="${BLUE_IMAGE}"; fi
if [[ "${OLD}" == green ]]; then OLD_IMAGE="${GREEN_IMAGE}"; fi
log "현재 칸: ${OLD} → 새 칸: ${TARGET}(127.0.0.1:${TARGET_PORT}), 이미지: ${IMAGE}"

# upstream 파일과 실제로 적용된 Nginx 설정이 다르면(지난 배포의 되돌리기가 끝나지 못한 경우 등) 멈춘다.
# 그대로 가면 지금 서비스 중인 칸을 새 칸으로 알고 다시 만들어 /api가 끊긴다
if [[ "${OLD}" != none ]]; then
  live_slot="$(nginx_slot)"
  if [[ -z "${live_slot}" ]]; then
    die "Nginx를 거친 응답에서 칸을 확인하지 못했습니다. Nginx가 꺼져 있거나 사이트 설정이 아직 적용되지 않았습니다(첫 배포가 Nginx 전환 전에 실패했다면 sudo nginx -t && sudo systemctl reload nginx 뒤 다시 실행)"
  fi
  [[ "${live_slot}" == "${OLD}" ]] \
    || die "upstream 파일은 ${OLD} 칸인데 Nginx는 ${live_slot} 칸으로 보냅니다. 지난 배포의 되돌리기가 끝나지 않았을 수 있습니다(${LOG_FILE}의 '모두 살려 둡니다'). 둘을 맞춘 뒤 다시 실행해 주세요"
fi

if [[ "${TARGET}" == blue ]]; then BLUE_IMAGE="${IMAGE}"; else GREEN_IMAGE="${IMAGE}"; fi
export BLUE_IMAGE GREEN_IMAGE

# 단계: init(아직 새 칸 없음) → prepare(새 칸 준비 중) → switched(Nginx가 새 칸을 봄, 확인 전) → committed(확인 끝)
# 실패·중단은 모두 EXIT trap 한 곳에서 단계에 맞게 되돌린다(set -e로 끝나는 경우 포함)
PHASE=init
UPSTREAM_BACKUP=""
KEEP_UPSTREAM_BACKUP=false
UPSTREAM_TMP=""
STATE_TMP=""

remove_target() {
  log "새 칸(${TARGET}) 로그 마지막 50줄:"
  compose logs --no-color --tail 50 "app-${TARGET}" 2>&1 | while IFS= read -r line; do log "  ${line}"; done
  if [[ "${OLD}" == none ]]; then
    log "새 칸(${TARGET})을 지웁니다"
  else
    log "새 칸(${TARGET})을 지웁니다. 서비스 중인 칸(${OLD})은 그대로입니다"
  fi
  compose rm --stop --force "app-${TARGET}" >/dev/null 2>&1 || true
}

# Nginx가 옛 칸으로 돌아간 것까지 확인하면 0. 실패하면 1이고, 그때는 Nginx가 어느 칸을 보는지 알 수 없으니
# 호출한 쪽이 새 칸을 지우면 안 된다
restore_upstream() {
  if [[ -z "${UPSTREAM_BACKUP}" ]]; then
    # 첫 배포: 되돌릴 이전 칸이 없다. upstream 파일은 남겨 둔다(지우면 사이트 설정의 include가 깨져
    # nginx를 재시작할 수 없게 된다). /api는 다음 배포가 성공할 때까지 502다
    log "첫 배포라 되돌릴 이전 칸이 없습니다. upstream 파일은 그대로 두고 다시 배포하면 됩니다"
    return 0
  fi
  local out
  if ! out="$(cp -p "${UPSTREAM_BACKUP}" "${UPSTREAM_FILE}" 2>&1)"; then
    log "경고: upstream 파일을 되돌리지 못했습니다: ${out}"
    return 1
  fi
  if ! run_logged "${NGINX_RELOAD_CMD}"; then
    log "경고: 되돌린 upstream으로 reload하지 못했습니다"
    return 1
  fi
  # reload는 신호만 보낸다. 실제로 옛 칸으로 돌아간 것을 본 뒤에야 새 칸을 지운다(6단계와 같은 이유)
  local deadline=$((SECONDS + NGINX_CHECK_TIMEOUT_SECONDS))
  until [[ "$(nginx_slot)" == "${OLD}" ]]; do
    if (( SECONDS >= deadline )); then
      log "경고: 되돌린 뒤 ${NGINX_CHECK_TIMEOUT_SECONDS}초 안에 Nginx가 ${OLD} 칸으로 보내는 것을 확인하지 못했습니다"
      return 1
    fi
    sleep 1
  done
  log "Nginx upstream을 ${OLD} 칸으로 되돌렸습니다"
  # 아직 새 칸으로 보내던 worker가 마무리할 시간을 준 뒤 새 칸을 지운다
  sleep "${DRAIN_SECONDS}"
  return 0
}

# 되돌리기를 끝내지 못했을 때: Nginx가 지금 어느 칸을 보는지 알 수 없으니 어느 칸도 지우지 않고
# (지금보다 나빠지지 않게) 손으로 할 일을 남긴다. 각 줄의 'N) ' 뒤부터 그대로 붙여 넣어 실행할 수 있다
keep_both_slots() {
  KEEP_UPSTREAM_BACKUP=true
  log "두 칸(${OLD}, ${TARGET})을 모두 살려 둡니다. 다음 배포 전에 아래를 직접 해 주세요"
  log "  1) sudo cp $(printf '%q' "${UPSTREAM_BACKUP}") $(printf '%q' "${UPSTREAM_FILE}")"
  log "  2) sudo ${NGINX_TEST_CMD} && sudo ${NGINX_RELOAD_CMD}"
  log "  3) ${NGINX_CHECK_CMD}   # 응답 헤더 X-Checkbook-Slot이 ${OLD}여야 한다"
  log "  4) sudo docker compose --project-directory $(printf '%q' "${SCRIPT_DIR}") -f $(printf '%q' "${COMPOSE_FILE}") --env-file $(printf '%q' "${ENV_FILE}") rm --stop --force app-${TARGET}"
  log "  5) sudo rm $(printf '%q' "${UPSTREAM_BACKUP}")"
}

on_exit() {
  local rc=$?
  # 되돌리는 동안 두 번째 Ctrl-C·끊김 신호로 중간에 멈추지 않게 한다
  trap '' INT TERM HUP
  set +e
  # 성공은 committed 뒤의 exit 0 하나뿐이다. 종료 코드가 아니라 단계로 판단한다
  # (신호로 죽어 종료 코드가 0으로 보이는 경우도 되돌리게)
  if [[ "${PHASE}" != committed ]]; then
    case "${PHASE}" in
      prepare) log "되돌립니다(단계 prepare, 종료 코드 ${rc})"; remove_target ;;
      switched)
        log "되돌립니다(단계 switched, 종료 코드 ${rc})"
        # 복원에 실패했는데 새 칸을 지우면, Nginx가 아직 새 칸을 보는 경우 /api가 전부 502가 된다
        if restore_upstream; then remove_target; else keep_both_slots; fi
        ;;
      *) ;;
    esac
  fi
  rm -f "${UPSTREAM_TMP}" "${STATE_TMP}"
  # 손으로 되돌릴 때 쓸 수 있게, 복원에 실패했으면 백업을 남긴다
  if [[ -n "${UPSTREAM_BACKUP}" && "${KEEP_UPSTREAM_BACKUP}" != true ]]; then rm -f "${UPSTREAM_BACKUP}"; fi
  return "${rc}"
}
trap on_exit EXIT
trap 'log "중단 신호를 받았습니다(단계 ${PHASE})"; exit 130' INT TERM HUP

log "1/7 이미지 받기"
docker pull --quiet "${IMAGE}" >/dev/null || die "이미지를 받지 못했습니다: ${IMAGE}"

log "2/7 DB 확인"
# 앱 배포 때 DB 설정이 바뀌었다고 postgres를 다시 만들지 않는다(옛 칸이 쓰는 중)
compose up -d --wait --no-recreate postgres || die "postgres가 healthy가 되지 않았습니다"

log "3/7 새 칸 기동"
PHASE=prepare
compose up -d --no-deps --force-recreate "app-${TARGET}" || die "새 칸을 띄우지 못했습니다"
CONTAINER_ID="$(compose ps -q "app-${TARGET}")"

log "4/7 헬스 확인(최대 ${HEALTH_TIMEOUT_SECONDS}초)"
deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
healthy=false
while (( SECONDS < deadline )); do
  if curl -fsS --max-time 2 "http://127.0.0.1:${TARGET_PORT}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    healthy=true
    break
  fi
  # 기동 실패(키 누락 등)면 재시작 정책 때문에 계속 다시 뜬다 → 90초를 기다리지 않고 바로 실패 처리
  read -r state restarts < <(docker inspect -f '{{.State.Status}} {{.RestartCount}}' "${CONTAINER_ID}" 2>/dev/null || echo "missing 0")
  if [[ "${state}" != running || "${restarts}" -gt 0 ]]; then
    log "새 칸이 멈췄거나 재시작 중입니다(상태 ${state}, 재시작 ${restarts}회)"
    break
  fi
  sleep "${HEALTH_INTERVAL_SECONDS}"
done
[[ "${healthy}" == true ]] || die "새 칸 헬스 확인 실패"
log "새 칸 헬스 UP"

log "5/7 Nginx 전환"
mkdir -p "$(dirname "${UPSTREAM_FILE}")"
if [[ -f "${UPSTREAM_FILE}" ]]; then
  # 대상 옆에 둔다(/tmp는 재부팅·정리로 사라질 수 있는데, 되돌리기에 실패하면 손으로 복원할 때 이 파일을 쓴다).
  # 사이트 설정은 upstream.conf를 이름으로 include해서 이 파일은 읽히지 않는다
  UPSTREAM_BACKUP="$(mktemp "${UPSTREAM_FILE}.bak.XXXXXX")"
  cp -p "${UPSTREAM_FILE}" "${UPSTREAM_BACKUP}"
fi
UPSTREAM_TMP="$(mktemp "${UPSTREAM_FILE}.XXXXXX")"
cat >"${UPSTREAM_TMP}" <<EOF
# deploy.sh가 쓴다. 손으로 고치지 않는다 ($(date '+%Y-%m-%d %H:%M:%S'), ${TARGET}, ${IMAGE})
upstream checkbook_app {
    server ${UPSTREAM_HOST}:${TARGET_PORT};
}
# 응답 헤더 X-Checkbook-Slot으로 나간다. deploy.sh가 전환이 실제로 적용됐는지 확인하는 데 쓴다
map \$host \$checkbook_slot {
    default "${TARGET}";
}
EOF
chmod 644 "${UPSTREAM_TMP}"
PHASE=switched
mv -f "${UPSTREAM_TMP}" "${UPSTREAM_FILE}"
UPSTREAM_TMP=""
run_logged "${NGINX_TEST_CMD}" || die "nginx -t 실패"
run_logged "${NGINX_RELOAD_CMD}" || die "nginx reload 실패"

log "6/7 Nginx를 거쳐 새 칸 확인(최대 ${NGINX_CHECK_TIMEOUT_SECONDS}초)"
# reload는 신호만 보내고 바로 돌아온다. 새 설정이 실제로 적용돼 새 칸이 응답하는지 본 뒤에 옛 칸을 끈다
deadline=$((SECONDS + NGINX_CHECK_TIMEOUT_SECONDS))
routed=false
while (( SECONDS < deadline )); do
  if bash -c "${NGINX_CHECK_CMD}" 2>/dev/null | tr -d '\r' | grep -qi "^x-checkbook-slot: ${TARGET}$"; then
    routed=true
    break
  fi
  sleep 1
done
[[ "${routed}" == true ]] || die "Nginx를 거친 요청이 새 칸(${TARGET})에 닿지 않습니다"
# 여기부터는 새 칸이 서비스 중이라 되돌리지 않는다. 남은 정리(상태 기록, 옛 칸 종료)가 신호로 끊기지 않게
# 신호부터 막고 단계를 바꾼다
trap '' INT TERM HUP
PHASE=committed
log "Nginx가 ${TARGET} 칸으로 보냅니다"

# 상태 파일은 기록용이라 실패해도 배포를 멈추지 않는다(서비스 중인 칸은 upstream 파일이 기준)
write_state() {
  STATE_TMP="$(mktemp "${STATE_FILE}.XXXXXX")" || return 1
  {
    echo "# deploy.sh가 쓴다 ($(date '+%Y-%m-%d %H:%M:%S')). 서비스 중인 칸은 upstream 파일이 기준이다"
    echo "ACTIVE=${TARGET}"
    echo "BLUE_IMAGE=${BLUE_IMAGE}"
    echo "GREEN_IMAGE=${GREEN_IMAGE}"
    echo "PREVIOUS_IMAGE=${OLD_IMAGE}"
  } >"${STATE_TMP}" || return 1
  chmod 600 "${STATE_TMP}" || return 1
  mv -f "${STATE_TMP}" "${STATE_FILE}" || return 1
  STATE_TMP=""
}
write_state || log "경고: 상태 파일(${STATE_FILE})을 쓰지 못했습니다. 서비스에는 영향 없고 다음 배포의 이미지 기록만 빠집니다"

log "7/7 옛 칸 종료"
if [[ "${OLD}" != none ]]; then
  sleep "${DRAIN_SECONDS}"
  # 그레이스풀 종료: 진행 중 요청은 마저 처리한다(compose의 stop_grace_period). 로그는 journald에 남는다
  compose rm --stop --force "app-${OLD}" >/dev/null || log "경고: 옛 칸(${OLD}) 종료 실패. 직접 확인해 주세요"
fi

if curl -fsS --max-time 10 -o /dev/null "${EXTERNAL_CHECK_URL}"; then
  log "외부 확인 통과: ${EXTERNAL_CHECK_URL}"
else
  # 교체는 끝났다. Cloudflare Bot Fight Mode가 서버발 curl을 막을 수 있어 경고만 한다
  log "경고: 외부 확인 실패(${EXTERNAL_CHECK_URL}). 브라우저로 화면을 확인해 주세요"
fi
log "배포 완료: ${TARGET} ← ${IMAGE}"
if [[ -n "${OLD_IMAGE}" ]]; then log "되돌리기: sudo $0 ${OLD_IMAGE}"; fi
exit 0
