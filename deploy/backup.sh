#!/usr/bin/env bash
# CheckBook DB 백업 (OCI 서버, root로 실행. cron이 매일 03:30에 부른다 — deploy/README.md)
#
# pg_dump 커스텀 형식(-Fc, 압축)으로 /var/backups/checkbook에 하루 1개를 쓰고 최근 7개만 남긴다(결정 카드 5).
# 서버 디스크에만 있어 서버째 사라지면 같이 사라진다. 사용자 데이터가 생기면 서버 밖 복사를 붙인다.
# 복원: pg_restore --clean --if-exists -d checkbook < 파일 (deploy/README.md '복원')
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${CHECKBOOK_ENV_FILE:-/opt/checkbook/.env}"
BACKUP_DIR="${CHECKBOOK_BACKUP_DIR:-/var/backups/checkbook}"
KEEP=7

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }

compose() {
  docker compose --project-directory "${SCRIPT_DIR}" -f "${SCRIPT_DIR}/compose.yaml" --env-file "${ENV_FILE}" "$@"
}

umask 077
mkdir -p "${BACKUP_DIR}"
# cron과 손으로 돌린 실행이 겹치면 덤프 두 개가 함께 돌고, 같은 초에 시작하면 같은 .partial 파일에 쓴다.
# 정리부터 오래된 백업 삭제까지 한 번에 하나만 돈다
exec 9>"${BACKUP_DIR}/.lock"
flock -n 9 || { log "실패: 다른 백업이 진행 중입니다(${BACKUP_DIR}/.lock)"; exit 1; }
# 강제 종료돼 남은 미완성 파일 정리
find "${BACKUP_DIR}" -maxdepth 1 -name 'checkbook-*.dump.partial' -type f -mmin +60 -delete

target="${BACKUP_DIR}/checkbook-$(date '+%Y%m%d-%H%M%S').dump"
partial="${target}.partial"
trap 'rm -f "${partial}"' EXIT

# 사용자 이름은 컨테이너 안의 POSTGRES_USER를 쓴다(.env 값을 셸로 읽지 않는다)
# shellcheck disable=SC2016  # $POSTGRES_USER는 컨테이너 안 셸이 풀어야 해서 작은따옴표가 맞다
compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d checkbook -Fc' >"${partial}"
[[ -s "${partial}" ]] || { log "실패: 덤프가 비어 있습니다"; exit 1; }
mv "${partial}" "${target}"
log "백업 완료: ${target} ($(du -h "${target}" | cut -f1))"

# 최근 KEEP개만 남긴다(파일 이름에 시각이 들어가 이름순 = 시간순)
mapfile -t old < <(find "${BACKUP_DIR}" -maxdepth 1 -name 'checkbook-*.dump' -type f | sort -r | tail -n +$((KEEP + 1)))
if (( ${#old[@]} > 0 )); then
  rm -f -- "${old[@]}"
  log "오래된 백업 ${#old[@]}개 삭제"
fi
