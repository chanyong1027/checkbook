#!/usr/bin/env bash
# 저장소의 Alloy 설정을 서버에 반영한다. 자동 배포에 넣지 않고 사람이 실행한다
# (배포 키에 /etc 쓰기 권한을 주지 않는다. Alloy가 멈추면 사이트는 돌지만 지표·로그·알림이 끊긴다).
#
# 사용: sudo /opt/checkbook/app/deploy/apply-alloy.sh [--diff] [--no-pull]
#       sudo /opt/checkbook/app/deploy/apply-alloy.sh --secrets    (처음 한 번, 토큰을 바꿀 때)
#       sudo /opt/checkbook/app/deploy/apply-alloy.sh --restart    (설정은 그대로 재시작만, 토큰을 바꾼 뒤)
#   --diff     바뀔 내용만 보여 주고 아무것도 바꾸지 않는다(서버 파일에 남은 토큰은 가려서 보여 준다)
#   --no-pull  저장소를 갱신하지 않고 지금 받아 둔 파일로 반영한다
#   --secrets  Grafana 주소·사용자 ID·토큰을 입력받아 secrets.env(root 600)에 쓰고, systemd drop-in이 그 파일을
#              EnvironmentFile=로 읽게 한다. Alloy는 재시작하지 않는다(처음이면 이어서 옵션 없이, 토큰 교체면 --restart)
#   --restart  반영과 같은 재시작·준비 확인만 한다(되돌릴 설정이 없으니 실패하면 알리기만 한다)
#
# 순서: 저장소 갱신 → alloy validate(문법·속성·참조) → 서버 파일과 비교(같으면 끝, --diff면 여기서 끝)
#       → 비밀값 파일 확인 → 잠금 → 백업 → 복사 → reset-failed + restart → /-/ready 확인(안 되면 되돌리고 다시 restart).
# validate는 sys.env 값이 비었는지는 보지 않는다 — 그래서 설정이 쓰는 이름이 secrets.env에 다 있는지 따로 본다.
set -euo pipefail

REPO="${CHECKBOOK_REPO:-/opt/checkbook/app}"
CONFIG_FILE="${CHECKBOOK_ALLOY_CONFIG:-/etc/alloy/config.alloy}"
SECRETS_FILE="${CHECKBOOK_ALLOY_SECRETS:-/etc/alloy/secrets.env}"
# 설치 스크립트가 만든 drop-in(Environment=로 토큰을 직접 넣음)을 같은 이름으로 덮어쓴다
DROPIN_FILE="${CHECKBOOK_ALLOY_DROPIN:-/etc/systemd/system/alloy.service.d/env.conf}"
BACKUP_ROOT="${CHECKBOOK_ALLOY_BACKUP_DIR:-/var/backups/checkbook-alloy}"
LOCK_FILE="${CHECKBOOK_ALLOY_LOCK_FILE:-/run/checkbook/apply-alloy.lock}"
ALLOY_BIN="${CHECKBOOK_ALLOY_BIN:-alloy}"
ALLOY_GROUP="${CHECKBOOK_ALLOY_GROUP:-alloy}"
RESTART_CMD="${CHECKBOOK_ALLOY_RESTART:-systemctl restart alloy}"
DAEMON_RELOAD_CMD="${CHECKBOOK_ALLOY_DAEMON_RELOAD:-systemctl daemon-reload}"
RESET_FAILED_CMD="${CHECKBOOK_ALLOY_RESET_FAILED:-systemctl reset-failed alloy}"
# Alloy 기본 HTTP 주소. 구성 요소를 다 불러온 뒤에야 200을 준다
READY_URL="${CHECKBOOK_ALLOY_READY_URL:-http://127.0.0.1:12345/-/ready}"
READY_TIMEOUT_SECONDS="${CHECKBOOK_ALLOY_READY_TIMEOUT:-30}"
KEEP_BACKUPS=10
SEND_CHECK="journalctl -u alloy --since '-3 min' | grep -iE 'error|401|403'"

die() { echo "[apply-alloy] 실패: $*" >&2; exit 1; }
log() { echo "[apply-alloy] $*"; }

# 화면에 나가는 설정 내용에서 토큰을 가린다(설치 스크립트 원본은 basic_auth password에 토큰을 평문으로 둔다)
mask() { sed -E 's/glc_[^"[:space:]]+/<TOKEN>/g; s/(password[[:space:]]*=[[:space:]]*)"[^"]*"/\1"<TOKEN>"/'; }

take_lock() {
  [[ -d "$(dirname "${LOCK_FILE}")" ]] || install -d -m 700 "$(dirname "${LOCK_FILE}")"
  exec 9>"${LOCK_FILE}"
  flock -n 9 || die "다른 apply-alloy.sh가 실행 중(${LOCK_FILE})"
}

wait_ready() {
  local deadline=$((SECONDS + READY_TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    if curl -fsS -m 2 -o /dev/null "${READY_URL}" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# 유닛은 Restart=always에 RestartSec 기본 100ms라, 곧 죽는 설정이면 몇 초 안에 시작 횟수 제한(기본 10초에 5회)에
# 걸린다. 걸린 동안은 손으로 하는 restart도 거절된다(start-limit-hit) — 직전에 누가 몇 번 재시작했어도 같다.
# 그러면 멀쩡한 새 설정이 "시작 실패"로 되돌려지므로 restart마다 횟수를 먼저 푼다.
# 9>&-: 잠금 fd를 restart가 띄우는 프로세스에 넘기지 않는다(넘어가면 그 프로세스가 사는 동안 잠금이 안 풀린다)
restart_alloy() {
  ${RESET_FAILED_CMD} 9>&- || true
  ${RESTART_CMD} 9>&-
}

setup_secrets() {
  local prom_url prom_user loki_url loki_user token
  read -rp  "Prometheus remote_write URL(https://…/api/prom/push): " prom_url
  read -rp  "Prometheus 사용자 ID(숫자): " prom_user
  read -rp  "Loki push URL(https://…/loki/api/v1/push): " loki_url
  read -rp  "Loki 사용자 ID(숫자): " loki_user
  read -rsp "Access policy 토큰(화면에 안 보임): " token; echo
  [[ "${prom_url}" == https://* && "${loki_url}" == https://* ]] || die "주소는 https://로 시작해야 한다"
  [[ "${prom_user}" =~ ^[0-9]+$ && "${loki_user}" =~ ^[0-9]+$ ]] || die "사용자 ID는 숫자다"
  [[ -n "${token}" ]] || die "토큰이 비었다"

  take_lock
  # 이미 있는 디렉터리의 권한은 건드리지 않는다(패키지가 정한 값 유지)
  [[ -d "$(dirname "${SECRETS_FILE}")" ]] || install -d -m 755 "$(dirname "${SECRETS_FILE}")"
  local tmp
  tmp="$(mktemp "${SECRETS_FILE}.XXXXXX")"
  # 중간에 실패해도 토큰이 든 임시 파일을 남기지 않는다. 경로는 등록할 때 펼친다 — set -e로 빠지면 trap이 함수 밖에서
  # 돌아 local tmp가 안 보인다(set -u로 trap까지 죽는다). mktemp 이름에는 따옴표가 들어가지 않는다
  # shellcheck disable=SC2064
  trap "rm -f -- '${tmp}'" EXIT
  chmod 600 "${tmp}"
  printf 'GRAFANA_PROM_URL=%s\nGRAFANA_PROM_USER=%s\nGRAFANA_LOKI_URL=%s\nGRAFANA_LOKI_USER=%s\nGRAFANA_CLOUD_TOKEN=%s\n' \
    "${prom_url}" "${prom_user}" "${loki_url}" "${loki_user}" "${token}" > "${tmp}"
  chown root:root "${tmp}"
  mv -f "${tmp}" "${SECRETS_FILE}"
  trap - EXIT
  unset token

  # drop-in에는 경로만 둔다. Environment=에 값을 쓰면 `systemctl show`로 일반 사용자도 읽는다.
  # systemd가 root로 이 파일을 읽어 Alloy 프로세스 환경에만 넣는다
  [[ -d "$(dirname "${DROPIN_FILE}")" ]] || install -d -m 755 "$(dirname "${DROPIN_FILE}")"
  printf '[Service]\nEnvironmentFile=%s\n' "${SECRETS_FILE}" > "${DROPIN_FILE}"
  chmod 644 "${DROPIN_FILE}"
  ${DAEMON_RELOAD_CMD} 9>&-
  log "비밀값 저장: ${SECRETS_FILE}(root 600), drop-in: ${DROPIN_FILE}. Alloy는 아직 이전 값으로 돈다"
  log "이어서: 처음이면 sudo $0, 토큰만 바꿨으면 sudo $0 --restart"
}

DIFF_ONLY=0
PULL=1
MODE=apply
for arg in "$@"; do
  case "${arg}" in
    --diff) DIFF_ONLY=1 ;;
    --no-pull) PULL=0 ;;
    --secrets) MODE=secrets ;;
    --restart) MODE=restart ;;
    *) die "알 수 없는 옵션: ${arg}" ;;
  esac
done

[[ "${EUID}" -eq 0 ]] || die "root로 실행한다(sudo)"
[[ "${MODE}" == apply || "${DIFF_ONLY}" -eq 0 ]] || die "--diff는 --secrets·--restart와 같이 쓰지 않는다"

case "${MODE}" in
  secrets)
    setup_secrets
    exit 0
    ;;
  restart)
    take_lock
    restart_alloy && wait_ready || die "${READY_TIMEOUT_SECONDS}초 안에 준비되지 않는다. 'systemctl status alloy', 'journalctl -u alloy -n 50'으로 확인"
    log "재시작 완료, Alloy 준비됨. 보내기 확인: ${SEND_CHECK}가 비어 있어야 한다"
    exit 0
    ;;
esac

if [[ "${PULL}" -eq 1 ]]; then
  log "저장소 갱신: ${REPO}"
  git -C "${REPO}" pull --ff-only --quiet || die "git pull 실패(로컬 변경이 있거나 fast-forward가 아님)"
fi
log "저장소 커밋: $(git -C "${REPO}" rev-parse --short HEAD 2>/dev/null || echo '알 수 없음')"

SRC="${REPO}/deploy/alloy/config.alloy"
[[ -f "${SRC}" ]] || die "저장소에 없음: ${SRC}"

"${ALLOY_BIN}" validate "${SRC}" || die "저장소 설정이 alloy validate에서 실패(서버는 그대로)"

if [[ -f "${CONFIG_FILE}" ]] && cmp -s "${SRC}" "${CONFIG_FILE}"; then
  log "변경 없음 — 서버 설정이 저장소와 같다"
  exit 0
fi
echo "----- ${CONFIG_FILE}"
diff -u "${CONFIG_FILE}" "${SRC}" 2>/dev/null | mask || true
if [[ "${DIFF_ONLY}" -eq 1 ]]; then
  log "--diff: 바뀔 예정(아무것도 바꾸지 않음)"
  exit 0
fi

# 설정이 sys.env로 읽는 이름이 비밀값 파일에 값과 함께 다 있어야 한다. 비면 Alloy가 빈 주소로 뜨지 못한다
[[ -f "${SECRETS_FILE}" ]] || die "${SECRETS_FILE}이 없다. 먼저: sudo $0 --secrets"
grep -qxF "EnvironmentFile=${SECRETS_FILE}" "${DROPIN_FILE}" 2>/dev/null \
  || die "${DROPIN_FILE}이 ${SECRETS_FILE}을 읽지 않는다. 먼저: sudo $0 --secrets"
for name in $(grep -oE 'sys\.env\("[A-Za-z0-9_]+"\)' "${SRC}" | sed -E 's/sys\.env\("(.*)"\)/\1/' | sort -u); do
  grep -qE "^${name}=.+" "${SECRETS_FILE}" || die "${SECRETS_FILE}에 ${name} 값이 없다. sudo $0 --secrets로 다시 쓴다"
done

take_lock

# 백업에는 이전 설정(설치 스크립트 원본이면 평문 토큰)이 들어가므로 root만 들어가는 디렉터리에 둔다
BACKUP_DIR="${BACKUP_ROOT}/$(date +%Y%m%d-%H%M%S)"
install -d -m 700 "${BACKUP_ROOT}" "${BACKUP_DIR}"
HAD_CONFIG=0
if [[ -f "${CONFIG_FILE}" ]]; then
  cp -p "${CONFIG_FILE}" "${BACKUP_DIR}/config.alloy"
  HAD_CONFIG=1
fi
log "백업: ${BACKUP_DIR}"

restore() {
  if [[ "${HAD_CONFIG}" -eq 1 ]]; then
    cp -p "${BACKUP_DIR}/config.alloy" "${CONFIG_FILE}"
  else
    rm -f -- "${CONFIG_FILE}"
  fi
}

install -D -m 640 -o root -g "${ALLOY_GROUP}" "${SRC}" "${CONFIG_FILE}"

# restart는 프로세스가 뜨기만 하면 성공을 돌려준다. 설정을 못 불러와 곧 죽는 경우는 /-/ready로 잡는다
if ! restart_alloy || ! wait_ready; then
  log "새 설정으로 ${READY_TIMEOUT_SECONDS}초 안에 준비되지 않아 되돌린다. 원인: journalctl -u alloy -n 50"
  restore
  if restart_alloy && wait_ready; then
    # 이전 설정이 이미 지운 토큰을 쓰면(설치 스크립트 원본 + quickstart 토큰 삭제 뒤) 떠도 보내지 못한다
    die "새 설정을 되돌렸고 Alloy는 이전 설정으로 돈다. 보내기 확인: ${SEND_CHECK}"
  fi
  die "되돌린 설정으로도 Alloy가 준비되지 않는다. 백업 ${BACKUP_DIR}, 'systemctl status alloy'로 확인"
fi
log "반영 완료, Alloy 준비됨. 1~2분 뒤 Grafana에서 지표·로그가 들어오는지 확인"
log "보내기 실패(주소·토큰 오류)는 준비 확인에 안 잡힌다: ${SEND_CHECK}가 비어 있어야 한다"

# 오래된 백업 정리(최근 ${KEEP_BACKUPS}개만)
find "${BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d | sort | head -n -"${KEEP_BACKUPS}" | xargs -r rm -rf --
