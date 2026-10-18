#!/usr/bin/env bash
# 저장소의 Nginx 설정을 서버에 반영한다. 자동 배포에 넣지 않고 사람이 실행한다
# (PR-O2 결정: 배포 키에 Nginx 설정 쓰기 권한을 주지 않고, 설정은 하나라 잘못 들어가면 사이트 전체가 멈춘다).
#
# 사용: sudo /opt/checkbook/app/deploy/apply-nginx.sh [--diff] [--no-pull]
#   --diff     바뀔 내용만 보여 주고 아무것도 바꾸지 않는다
#   --no-pull  저장소를 갱신하지 않고 지금 받아 둔 파일로 반영한다
#
# 순서: 저장소 갱신 → 서버 파일과 비교(같으면 끝) → 배포 잠금 → 백업 → 복사 → nginx -t(실패면 되돌림) → reload.
# upstream.conf(지금 서비스 중인 칸)는 deploy.sh가 관리하므로 건드리지 않는다.
set -euo pipefail

REPO="${CHECKBOOK_REPO:-/opt/checkbook/app}"
SITE_FILE="${CHECKBOOK_NGINX_SITE:-/etc/nginx/sites-available/checkbook}"
INCLUDE_DIR="${CHECKBOOK_NGINX_INCLUDE_DIR:-/etc/nginx/checkbook}"
BACKUP_ROOT="${CHECKBOOK_NGINX_BACKUP_DIR:-/var/backups/checkbook-nginx}"
# deploy.sh와 같은 잠금 — 배포가 Nginx를 전환하는 중에 설정을 바꾸지 않는다
LOCK_FILE="${CHECKBOOK_LOCK_FILE:-/run/checkbook/deploy.lock}"
NGINX_BIN="${CHECKBOOK_NGINX_BIN:-nginx}"
RELOAD_CMD="${CHECKBOOK_NGINX_RELOAD:-systemctl reload nginx}"
KEEP_BACKUPS=10

die() { echo "[apply-nginx] 실패: $*" >&2; exit 1; }
log() { echo "[apply-nginx] $*"; }

DIFF_ONLY=0
PULL=1
for arg in "$@"; do
  case "${arg}" in
    --diff) DIFF_ONLY=1 ;;
    --no-pull) PULL=0 ;;
    *) die "알 수 없는 옵션: ${arg}" ;;
  esac
done

[[ "${EUID}" -eq 0 ]] || die "root로 실행한다(sudo)"

if [[ "${PULL}" -eq 1 ]]; then
  log "저장소 갱신: ${REPO}"
  git -C "${REPO}" pull --ff-only --quiet || die "git pull 실패(로컬 변경이 있거나 fast-forward가 아님)"
fi
log "저장소 커밋: $(git -C "${REPO}" rev-parse --short HEAD 2>/dev/null || echo '알 수 없음')"

SRC="${REPO}/deploy/nginx"
# 저장소 파일 → 서버 위치
declare -a PAIRS=(
  "${SRC}/checkbook.conf|${SITE_FILE}"
  "${SRC}/cloudflare-realip.conf|${INCLUDE_DIR}/cloudflare-realip.conf"
  "${SRC}/security-headers.conf|${INCLUDE_DIR}/security-headers.conf"
  "${SRC}/proxy-app.conf|${INCLUDE_DIR}/proxy-app.conf"
)

declare -a CHANGED=()
for pair in "${PAIRS[@]}"; do
  src="${pair%%|*}"; dst="${pair##*|}"
  [[ -f "${src}" ]] || die "저장소에 없음: ${src}"
  if [[ ! -f "${dst}" ]] || ! cmp -s "${src}" "${dst}"; then
    CHANGED+=("${pair}")
    echo "----- ${dst}"
    diff -u "${dst}" "${src}" 2>/dev/null || true
  fi
done

if [[ "${#CHANGED[@]}" -eq 0 ]]; then
  log "변경 없음 — 서버 설정이 저장소와 같다"
  exit 0
fi
if [[ "${DIFF_ONLY}" -eq 1 ]]; then
  log "--diff: ${#CHANGED[@]}개 파일이 바뀔 예정(아무것도 바꾸지 않음)"
  exit 0
fi

[[ -d "$(dirname "${LOCK_FILE}")" ]] || install -d -m 700 "$(dirname "${LOCK_FILE}")"
exec 9>"${LOCK_FILE}"
log "배포 잠금 대기(최대 120초)"
flock -w 120 9 || die "배포가 진행 중이라 잠금을 얻지 못함(${LOCK_FILE}). 배포가 끝난 뒤 다시 실행"

BACKUP_DIR="${BACKUP_ROOT}/$(date +%Y%m%d-%H%M%S)"
install -d -m 700 "${BACKUP_DIR}"
declare -a NEW_FILES=()
for pair in "${CHANGED[@]}"; do
  dst="${pair##*|}"
  if [[ -f "${dst}" ]]; then
    cp -p "${dst}" "${BACKUP_DIR}/$(echo "${dst}" | tr '/' '_')"
  else
    NEW_FILES+=("${dst}")
  fi
done
log "백업: ${BACKUP_DIR}"

restore() {
  for pair in "${CHANGED[@]}"; do
    dst="${pair##*|}"
    backup="${BACKUP_DIR}/$(echo "${dst}" | tr '/' '_')"
    if [[ -f "${backup}" ]]; then
      cp -p "${backup}" "${dst}"
    fi
  done
  for created in "${NEW_FILES[@]}"; do
    rm -f -- "${created}"
  done
}

for pair in "${CHANGED[@]}"; do
  src="${pair%%|*}"; dst="${pair##*|}"
  install -D -m 644 "${src}" "${dst}"
done

if ! "${NGINX_BIN}" -t; then
  restore
  "${NGINX_BIN}" -t >/dev/null 2>&1 || die "새 설정이 nginx -t에서 실패했고, 되돌린 설정도 실패한다. 백업 ${BACKUP_DIR}로 손으로 확인"
  die "새 설정이 nginx -t에서 실패해서 되돌렸다(reload 안 함). 서비스는 이전 설정 그대로"
fi

if ! ${RELOAD_CMD}; then
  restore
  ${RELOAD_CMD} || true
  die "reload 실패로 되돌렸다. 'systemctl status nginx'로 확인"
fi
log "반영 완료: ${#CHANGED[@]}개 파일, reload 함"

# 오래된 백업 정리(최근 ${KEEP_BACKUPS}개만)
find "${BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d | sort | head -n -"${KEEP_BACKUPS}" | xargs -r rm -rf --
