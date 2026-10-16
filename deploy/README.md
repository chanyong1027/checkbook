# 운영 배포 (OCI 서버)

서버 한 대(OCI A1, ARM64, Ubuntu 24.04)에 Cloudflare → Nginx → 앱 블루·그린 두 칸 → PostgreSQL 구조로 올린다.

```
사용자 ─► Cloudflare ─► Nginx(호스트, 443) ─┬─► app-blue  127.0.0.1:8081 ─┐
          (Full strict,     /api → 지금 칸   └─► app-green 127.0.0.1:8082 ─┴─► postgres(내부 네트워크만)
           CF 대역만 허용)   / → 정적 파일(/var/www/checkbook)
```

| 파일 | 서버 위치 | 하는 일 |
|---|---|---|
| `compose.yaml` | 이 폴더 그대로 | postgres + 앱 두 칸. 로그는 journald |
| `deploy.sh` | 이 폴더 그대로 | digest를 받아 쉬는 칸에 띄우고 헬스 확인 뒤 Nginx를 넘긴다 |
| `backup.sh` | 이 폴더 그대로 | 매일 `pg_dump`, 최근 7개 보관 |
| `nginx/checkbook.conf` | `/etc/nginx/sites-available/checkbook` | 사이트 설정 |
| `nginx/cloudflare-realip.conf`, `security-headers.conf`, `proxy-app.conf` | `/etc/nginx/checkbook/` | 사이트 설정이 include |
| (deploy.sh가 씀) | `/etc/nginx/checkbook/upstream.conf` | 지금 서비스 중인 칸 |
| (deploy.sh가 씀) | `/opt/checkbook/deploy-state.env` | 칸별 이미지 기록 |
| (사용자가 씀) | `/opt/checkbook/.env` (root 600) | 키·DB 계정 |

서버에는 저장소를 `/opt/checkbook/app`에 clone해 두고 `git pull`로 받는다(공개 저장소라 비밀값이 없고, 스크립트가 늘 main과 같다). 그래서 아래 명령의 `<deploy 폴더>`는 `/opt/checkbook/app/deploy`다. 스크립트는 자기 위치를 기준으로 `compose.yaml`을 찾는다.

## `.env`
```
DB_USERNAME=checkbook
DB_PASSWORD=<openssl rand -hex 32 로 만든 값>
YES24_KEY=<값>
KAKAO_BOOK_REST_API_KEY=<값>
DATANARU_AUTH_KEY=<값>
CORS_ALLOWED_ORIGINS=https://checkbook.site
```
`DB_URL`은 Compose가 넣는다(`postgres:5432`). 키 셋 중 하나라도 비면 앱이 뜨지 않는다. 값에 `$`가 있으면 작은따옴표로 감싼다(Compose가 `$`를 변수로 읽는다). 비밀번호에 `${`가 들어가면 Spring도 변수로 읽으니 위처럼 16진수로 만든다.

`DB_PASSWORD`는 DB를 **처음 만들 때만** 적용된다. 나중에 바꾸려면 `.env`만 고치지 말고 DB 안에서 `ALTER USER checkbook PASSWORD '...'`도 해야 한다(안 그러면 새 칸이 인증 실패로 안 뜬다).

## 평소 배포
1. GitHub Actions의 **Backend Image** 실행 요약에서 digest를 복사한다(main 머지 때 자동, 수동 실행도 가능).
2. 서버에서
   ```
   sudo git -C /opt/checkbook/app pull --ff-only    # 배포 파일을 main과 맞춘다
   cd /opt/checkbook/app/deploy
   sudo ./deploy.sh sha256:<digest>
   ```
   `deploy/nginx/`가 바뀐 커밋이면 `/etc/nginx/`의 복사본도 같이 고친다(첫 배포 3·4단계).
3. 마지막 줄이 `배포 완료`면 끝이다. 실패하면 새 칸만 지워지고 옛 칸이 계속 서비스한다. 로그 50줄이 같이 나온다.

진행 기록은 `/var/log/checkbook-deploy.log`에도 남는다. SSH가 끊겨도 스크립트가 되돌리기는 하지만, 안전하게 `tmux` 안에서 돌린다. 서비스 중인 칸은 `/etc/nginx/checkbook/upstream.conf`가 기준이다(상태 파일은 이미지 기록용).

되돌리기는 이전 digest로 같은 명령을 실행한다(성공 메시지 마지막 줄에 직전 이미지가 나온다).

## 첫 배포 (한 번만)
0. GHCR 패키지 공개: 첫 **Backend Image** 실행 뒤 GitHub 프로필 → Packages → `checkbook` → Package settings → Change visibility → Public. (새 패키지는 비공개로 생겨서 서버가 토큰 없이 받지 못한다. 결정 카드 12)
1. 저장소 받기: `sudo git clone https://github.com/chanyong1027/checkbook.git /opt/checkbook/app` (root 소유, 실행 권한은 git이 그대로 가져온다).
2. 인증서: Cloudflare Origin CA 인증서·개인 키가 `/etc/ssl/cloudflare/origin.pem`·`origin.key`(키는 root 600)에 있는지 확인한다(서버 준비 때 넣음).
3. Nginx 공용 파일: `sudo install -d -m 755 /etc/nginx/checkbook` 후 `nginx/` 안의 include 파일 3개(`cloudflare-realip.conf`, `security-headers.conf`, `proxy-app.conf`)를 거기에 복사.
4. 사이트 연결(**아직 reload하지 않는다**): `nginx/checkbook.conf`를 `/etc/nginx/sites-available/checkbook`로 복사하고 `sites-enabled`에 링크. `sites-enabled`의 `default`(Ubuntu 기본 사이트, `default_server`가 겹쳐 `nginx -t`가 실패함)와 서버 준비 때 만든 임시 사이트 `checkbook-prepare` 링크는 지운다. 사이트 설정이 include하는 `upstream.conf`는 다음 단계에서 생기므로 지금 `nginx -t`는 실패하는 게 정상이다. 이 상태에서는 nginx를 재시작할 수 없으니 4~6단계를 이어서 한다. 첫 배포를 그만둘 때는 `sites-enabled/checkbook` 링크를 지우고 `checkbook-prepare`를 다시 링크한다. 첫 배포가 Nginx 전환 뒤에 실패하면 `upstream.conf`는 남겨 두고(그래야 nginx가 재시작된다) /api만 502인 상태로 끝나니 다시 실행하면 된다.
5. `.env`에 DB 계정과 키를 채운다.
6. `sudo ./deploy.sh sha256:<digest>` → postgres가 처음 뜨고, 앱이 Flyway로 스키마를 만든 뒤 blue 칸이 뜬다. 스크립트가 `upstream.conf`를 쓰고 `nginx -t`·reload까지 한다.
7. 프런트 정적 파일을 `/var/www/checkbook`에 둔다(별도 안내, 자동화는 PR-O2).
8. 첫 데이터(공공도서관·전자도서관 목록)를 넣는다(별도 안내).
9. 백업 cron: `/etc/cron.d/checkbook-backup`에
   ```
   30 3 * * * root /bin/bash <deploy 폴더>/backup.sh 2>&1 | systemd-cat -t checkbook-backup
   ```
   서버 시간대가 Asia/Seoul이라(`timedatectl`로 확인) 03:30 KST다. 앱의 03:00 정리 배치·04:00 추천 갱신과 겹치지 않는다. `/bin/bash`로 부르는 건 실행 권한이 빠져도 돌게 하려는 것.

## 로그
```
journalctl CONTAINER_NAME=checkbook-app-blue-1 -f     # 앱(칸 이름은 deploy-state.env의 ACTIVE)
journalctl CONTAINER_NAME=checkbook-postgres-1
journalctl -t checkbook-backup                          # 백업
sudo docker logs checkbook-app-green-1                  # docker logs도 된다
```
journald로 보내서 칸 컨테이너를 지워도 로그가 남는다. 보관량은 journald 기본값(파일시스템의 10%, 최대 4GB)을 따른다.

## 요청 제한 dry run 끄기
`checkbook.conf`의 `limit_req_dry_run on;`은 재가동 후 1~2주 관찰용이다. `/var/log/nginx/error.log`에서 `limiting requests, dry run`을 보고 실제 사용자가 걸리지 않으면 `off`로 바꾸고 reload한다.

## 백업 복원
앱 칸을 먼저 멈춘다(연결이 남아 있으면 `--clean`이 잠금을 기다리거나 일부만 복원된다). 서비스가 잠깐 멈춘다.
```
sudo docker compose --project-directory <deploy 폴더> --env-file /opt/checkbook/.env stop app-blue app-green
sudo docker compose --project-directory <deploy 폴더> --env-file /opt/checkbook/.env exec -T postgres \
  sh -c 'pg_restore -U "$POSTGRES_USER" -d checkbook --clean --if-exists --single-transaction' < /var/backups/checkbook/checkbook-<시각>.dump
```
그다음 서비스 중이던 칸의 digest로 `deploy.sh`를 다시 실행한다.

## Cloudflare 대역 갱신
대역은 https://www.cloudflare.com/ips-v4 , https://www.cloudflare.com/ips-v6 에 있다. 바뀌면 `nginx/cloudflare-realip.conf`와 OCI 보안 목록(80·443 수신)을 같이 고친다.

## 로컬에서 검증할 때
journald가 없는 Docker Desktop에서는 `CHECKBOOK_LOG_DRIVER=json-file`로 돌린다. `deploy.sh`의 경로·명령은 `CHECKBOOK_*` 환경 변수로 바꿀 수 있다(스크립트 위쪽 참고).
