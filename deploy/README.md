# 운영 배포 (OCI 서버)

서버 한 대(OCI A1, ARM64, Ubuntu 24.04)에 Cloudflare → Nginx → 앱 블루·그린 두 칸 → PostgreSQL 구조로 올린다.

```
사용자 ─► Cloudflare ─► Nginx(호스트, 443) ─┬─► app-blue  127.0.0.1:8081 ─┐
          (Full strict,     /api → 지금 칸   └─► app-green 127.0.0.1:8082 ─┴─► postgres(내부 네트워크만)
           CF 대역만 허용)   / → 정적 파일(/var/www/checkbook → 지금 릴리스)
```

평소 배포는 자동이다. main에 머지되면 GitHub Actions가 이미지를 만들고 SSH로 서버의 고정 래퍼 `checkbook-deploy`를 부른다. 손으로 할 일은 되돌리기와 고장 났을 때뿐이다.

| 파일 | 서버 위치 | 하는 일 |
|---|---|---|
| `server/checkbook-deploy` | `/usr/local/sbin/checkbook-deploy` (root 755, **손으로 설치**) | 배포 래퍼. 입력 검사, main 조상·하한선·순서 검사, 장부, 프런트 릴리스 |
| `compose.yaml`, `deploy.sh`, `backup.sh` | `/opt/checkbook/releases/<커밋>/deploy/` | 이미지 라벨 커밋의 것을 래퍼가 꺼내 쓴다 |
| `STATE_FORMAT_VERSION` | (커밋 안) | 되돌리기 하한선용 호환성 버전(정수) |
| `nginx/checkbook.conf` | `/etc/nginx/sites-available/checkbook` | 사이트 설정(`apply-nginx.sh`로 손으로 반영) |
| `nginx/cloudflare-realip.conf`, `security-headers.conf`, `proxy-app.conf` | `/etc/nginx/checkbook/` | 사이트 설정이 include(`apply-nginx.sh`로 손으로 반영) |
| `apply-nginx.sh` | (저장소 `/opt/checkbook/app/deploy/`에서 실행) | 위 Nginx 파일 반영: 비교·백업·검사·되돌리기·reload |
| `alloy/config.alloy` | `/etc/alloy/config.alloy` | 지표·로그 수집 설정(`apply-alloy.sh`로 손으로 반영, 아래 '관측: Grafana Alloy') |
| `apply-alloy.sh` | (저장소 `/opt/checkbook/app/deploy/`에서 실행) | Alloy 설정 반영: 비밀값 확인·validate·백업·restart·준비 확인·되돌리기 |
| (deploy.sh가 씀) | `/etc/nginx/checkbook/upstream.conf` | 지금 서비스 중인 칸 |
| (deploy.sh가 씀) | `/opt/checkbook/deploy-state.env` | 칸별 이미지 기록 |
| (래퍼가 씀) | `/opt/checkbook/current` → `releases/<커밋>` | 서비스 중인 백엔드 커밋의 배포 파일(백업 cron이 여기를 부른다) |
| (래퍼가 씀) | `/opt/checkbook/ledger/{backend,frontend}/` | 장부: `current`(지금 버전), `pending`(배포 중), `history.log` |
| (래퍼가 씀) | `/opt/checkbook/rollback-floor` | 되돌리기 하한선(호환성 버전, 커밋). 올리기만 한다 |
| (래퍼가 씀) | `/var/www/checkbook` → `/var/www/checkbook-releases/<커밋>-<digest 12자리>` | 프런트 지금 릴리스(최근 3개 보관) |
| (래퍼가 씀) | `/var/www/checkbook-assets/` | 릴리스들이 같이 쓰는 `assets`(추가만, 안 쓰인 지 7일 뒤 삭제) |
| (사용자가 씀) | `/opt/checkbook/.env` (root 600) | 키·DB 계정 |

`/opt/checkbook`은 root 700이라 `ubuntu` 계정으로 `cd`할 수 없다. 아래 명령은 모두 전체 경로로 쓴다.

## 자동 배포
- 백엔드: **Backend Image** 워크플로. `src/**`, Gradle 파일, `Dockerfile`, `deploy/compose.yaml`·`deploy.sh`·`backup.sh`·`STATE_FORMAT_VERSION`이 바뀐 커밋이 main에 들어오면 테스트 → ARM64 빌드 → Trivy(고칠 수 있는 CRITICAL이면 멈춤) → **검사한 이미지 그대로** push → `backend deploy <digest>`.
- 프런트: **Frontend Deploy** 워크플로. `frontend/**`가 바뀌면 테스트·빌드 → 산출물 이미지(`FROM scratch`, 빌드 결과만) push → `frontend deploy <digest>`.
- `deploy/server/**`, `deploy/nginx/**`는 자동 배포하지 않는다. 바뀌면 서버에 손으로 적용한다(아래 '래퍼 설치').

래퍼가 하는 일(백엔드 기준, 프런트도 같은 틀):
1. "배포 중" 기록(`ledger/<영역>/pending`)이 남아 있으면 거부한다(→ `repair`).
2. 고정 저장소에서 digest로 이미지를 받고, revision 라벨의 커밋이 `origin/main`의 조상인지 본다.
3. 백엔드는 커밋이 하한선의 후손인지 항상 보고, 자동 배포(`deploy`)면 지금 버전의 후손인지도 본다(늦게 끝난 옛 빌드가 최신을 덮지 않게). 수동 되돌리기(`rollback`)는 후손 검사를 하지 않는다.
4. "배포 중" 기록을 남긴다. 남기지 못하면 시작하지 않는다.
5. 백엔드: 그 커밋의 `deploy/`를 `releases/<커밋>`에 꺼내 그 안의 `deploy.sh`를 돌린다. 프런트: 산출물을 꺼내 구조를 검사하고(링크·`..`·특수 파일·크기 50MB·파일 1,000개 상한) 릴리스 폴더를 만든 뒤 링크를 원자적으로 바꾸고, Nginx를 거친 `/`가 새 `index.html`인지 확인한다.
6. 성공하면 장부를 확정한다(백엔드는 `current` 링크와 하한선도). 실패하면 이전 버전이 실제로 서비스 중인지(Nginx 응답 칸 + 그 칸 컨테이너 digest / 링크 + 실제 `index.html`) 확인한 경우에만 "배포 중" 기록을 지운다. 확인하지 못하면 기록을 남기고 다음 배포를 막는다.

실제 작업은 `systemd-run` 유닛(`checkbook-deploy-backend`, `checkbook-deploy-frontend`)에서 돈다. SSH가 끊기거나 Actions가 취소·시간 초과돼도 서버 쪽 배포는 끝까지 간다. 이때 Actions 단계는 실패로 보이니 결과는 서버에서 확인한다. 유닛은 30분이 지나면 멈추고(SIGTERM → `deploy.sh`가 되돌림. 이때 Actions에는 종료 코드 1로 보인다), 래퍼의 이미지 받기는 10분까지만 기다린다. `deploy.sh` 안의 이미지 받기·DB 기동 대기는 따로 상한이 없고 이 30분이 막는다.

Actions 로그(공개)에는 진행 단계 줄만 나온다. 앱 로그·명령 출력은 키나 설정값이 섞일 수 있어 서버 `/var/log/checkbook-deploy.log`에만 남긴다.

배포를 시작할 때 장부가 실제 상태(Nginx 칸 + 컨테이너 digest / 링크 + 실제 `index.html`)와 맞는지 먼저 본다. 다르면(예: `deploy.sh`를 직접 돌린 뒤) 아무것도 바꾸지 않고 거부한다 → `repair`로 맞춘다.
```
sudo journalctl -u checkbook-deploy-backend -n 100 --no-pager
sudo cat /opt/checkbook/ledger/backend/current
sudo tail /opt/checkbook/ledger/backend/history.log
```

## 되돌리기·재배포
Actions → **Backend Image**(또는 **Frontend Deploy**) → Run workflow → `digest`에 되돌릴 digest를 넣는다(각 실행의 요약 화면, 또는 서버 `history.log`). 빌드 없이 `rollback`만 한다.
- 백엔드는 **하한선보다 옛 커밋으로는 되돌릴 수 없다**(상태 파일·upstream 형식이 바뀐 뒤 옛 스크립트가 새 상태를 망가뜨리지 않게).
- 서버에서 직접 할 때(Actions를 못 쓸 때): `sudo /usr/local/sbin/checkbook-deploy backend rollback sha256:<64자리>`. 검사는 같다.
- 최후 수단으로 `deploy.sh`를 직접 부를 수 있다(`sudo /bin/bash /opt/checkbook/current/deploy/deploy.sh ghcr.io/chanyong1027/checkbook@sha256:...`). 먼저 `systemctl is-active checkbook-deploy-backend`가 `inactive`인지 본다(하한선 커밋 `0be2ecc` 같은 옛 `deploy.sh`는 잠금 파일 위치가 달라 래퍼 유닛과 서로 막지 못한다). 끝나면 장부가 실제와 어긋나 다음 배포가 거부되니 `sudo /usr/local/sbin/checkbook-deploy repair backend`로 실제를 장부로 받아들인다.

## 배포 중 기록이 남았을 때 (repair)
배포가 "이전 상태로 돌아갔다"고 확인하지 못하고 끝나면(전환 중 서버 재시작, 유닛 강제 종료, 장부 쓰기 실패, Nginx 꺼짐 등) 다음 배포가 `repair` 안내와 함께 거부된다.
```
sudo /usr/local/sbin/checkbook-deploy repair backend     # 또는 frontend
```
"배포 중" 기록이 없을 때 `repair`는 장부와 실제를 대조해, 다르면 **실제를 장부로 받아들인다**(그 커밋이 main에 있고 하한선 이상일 때만. 아니면 멈춘다. 프런트는 전체 digest를 알 수 없어 장부 digest가 `none`이 된다).

`repair`는 장부와 **실제 상태**를 같이 본다(설정 파일만 보지 않는다. upstream 파일은 새 칸인데 reload가 실패해 실제 요청은 옛 칸으로 갈 수 있다).
- 실제 = 대상 버전 → 배포 성공으로 확정(하한선·`current` 링크도 맞춤)
- 실제 = 이전 버전 → "배포 중" 기록만 지움
- 둘 다 아님 → 아무것도 바꾸지 않고 상태만 출력한다. Nginx 칸(`upstream.conf`와 `curl -sk -D - --resolve checkbook.site:443:127.0.0.1 https://checkbook.site/api/featured?type=BESTSELLER`의 `X-Checkbook-Slot`)과 칸 컨테이너, 또는 `/var/www/checkbook` 링크를 손으로 맞춘 뒤 다시 `repair`

## `STATE_FORMAT_VERSION` 규칙
**상태 파일(`deploy-state.env`)이나 upstream 파일 형식을 바꾸는 PR은 `deploy/STATE_FORMAT_VERSION`을 1 올린다.** 그 커밋이 처음 배포에 성공하면 그 커밋이 새 하한선이 되고, 그보다 옛 버전으로는 래퍼로 되돌릴 수 없다. 하한선은 서버 파일에만 있고 올리기만 한다(되돌릴 커밋의 파일은 읽지 않는다).

## 래퍼 설치 (PR-O2, 한 번)
명령은 한 줄씩 붙여 넣는다. `<…>`는 바꿔 넣는다.

1. python3 확인(프런트 산출물 검사에 쓴다): `python3 -c 'import tarfile'`가 아무것도 출력하지 않으면 된다.
2. 배포 계정:
   ```
   sudo adduser --system --group --shell /bin/bash --home /home/deploy deploy
   sudo chown root:root /home/deploy
   sudo chmod 755 /home/deploy
   sudo install -d -o root -g root -m 755 /home/deploy/.ssh
   ```
   홈·`.ssh`를 root 소유로 둔다. 배포 계정이 자기 키 설정이나 셸 시작 파일(강제 명령도 로그인 셸이 실행한다)을 바꾸지 못하게 하는 겹 방어다.
3. 래퍼(저장소의 `deploy/server/checkbook-deploy`를 서버로 옮긴 뒤):
   ```
   sudo install -o root -g root -m 755 checkbook-deploy /usr/local/sbin/checkbook-deploy
   ```
   래퍼를 바꾸는 PR이 머지되면 이 단계만 다시 한다(자동 갱신하지 않는다).
4. sudoers:
   ```
   echo 'deploy ALL=(root) NOPASSWD: /usr/local/sbin/checkbook-deploy' | sudo tee /etc/sudoers.d/checkbook-deploy
   sudo chmod 440 /etc/sudoers.d/checkbook-deploy
   sudo visudo -c
   ```
5. 배포 키: PC에서 `ssh-keygen -t ed25519 -N '' -C checkbook-deploy -f checkbook-deploy` → 공개 키(`.pub`) 한 줄을 서버 `/home/deploy/.ssh/authorized_keys`에 이렇게 넣는다(앞부분이 강제 명령. pty·포워딩을 다 끈다):
   ```
   restrict,command="/usr/local/sbin/checkbook-deploy" ssh-ed25519 AAAA... checkbook-deploy
   ```
   ```
   sudo chown root:root /home/deploy/.ssh/authorized_keys
   sudo chmod 644 /home/deploy/.ssh/authorized_keys
   ```
6. 호스트 키 확인: 서버에서 `cat /etc/ssh/ssh_host_ed25519_key.pub` → `ssh-ed25519 AAAA...` 부분(앞 두 칸)을 적어 둔다.
7. GitHub → Settings → **Environments** → `production` 만들기(저장소 Secrets 메뉴가 아니다): Deployment branches = `main`만, 승인 대기 없음.
   - Environment secrets:
     - `DEPLOY_SSH_KEY`: 5번 개인 키 파일 전체
     - `DEPLOY_HOST`: 서버 주소(IP. 로그에 찍히지 않게 시크릿에 둔다)
     - `DEPLOY_HOST_KEY`: 6번 한 줄(`ssh-ed25519 AAAA...`)
   - Environment variables: `DEPLOY_USER` = `deploy`(비밀이 아니다. 시크릿에 두면 로그의 모든 "deploy" 글자가 `***`로 가려진다)
   - 넣기 전에 PC에서 같은 값으로 시험해 두면 좋다: `"<IP> <ssh-ed25519 AAAA…>"`를 파일에 써서 `ssh -o StrictHostKeyChecking=yes -o UserKnownHostsFile=<그 파일> -i <키> deploy@<IP> id` → `허용하지 않는 명령입니다`가 나오면 두 값이 맞다. 워크플로는 앞뒤 공백·줄바꿈을 지우고, 형식이 틀리면 값은 찍지 않고 어느 시크릿이 틀렸는지 알려 준다.
8. 확인: PC에서 `ssh -i checkbook-deploy deploy@<서버> id` → `허용하지 않는 명령입니다`가 나와야 한다(셸이 열리면 안 된다).
9. 장부 만들기(지금 손으로 운영 중인 상태를 그대로 옮긴다):
   ```
   sudo /usr/local/sbin/checkbook-deploy init <프런트를 빌드한 커밋 40자리>
   ```
   백엔드는 Nginx가 보내는 칸의 이미지에서 커밋을 읽어 장부·하한선·`current` 링크를 만든다. 프런트는 지금 `/var/www/checkbook` 폴더를 첫 릴리스로 옮기고 링크로 바꾼다(원래 폴더는 `/var/www/checkbook-before-init`에 남는다. 화면을 확인한 뒤 지운다). 백업 cron도 `/opt/checkbook/current/deploy/backup.sh`로 바꾼다(이전 내용은 `/opt/checkbook/ledger/cron-before-init`).
10. 프런트 GHCR 패키지 공개: 첫 **Frontend Deploy** 실행 뒤 GitHub 프로필 → Packages → `checkbook-frontend` → Package settings → Change visibility → Public. 새 패키지는 비공개로 생겨 첫 배포 단계는 이미지를 받지 못하고 실패한다. 공개로 바꾼 뒤 그 실행의 digest로 수동 실행(Run workflow)한다.

## 처음부터 서버를 다시 만들 때 (첫 배포)
지금 서버는 10-17에 이렇게 만들었다. 서버를 새로 만들 때만 쓴다.
0. GHCR 패키지 공개(백엔드 `checkbook`. 위 10번과 같은 방법).
1. 저장소 받기: `sudo git clone https://github.com/chanyong1027/checkbook.git /opt/checkbook/app` (래퍼가 `git fetch`·`git archive`에 쓴다. 작업 트리는 쓰지 않는다).
2. 인증서: Cloudflare Origin CA 인증서·개인 키가 `/etc/ssl/cloudflare/origin.pem`·`origin.key`(키는 root 600)에 있는지 확인한다.
3. Nginx 공용 파일: `sudo install -d -m 755 /etc/nginx/checkbook` 후 `deploy/nginx/` 안의 include 파일 3개를 거기에 복사.
4. 사이트 연결(**아직 reload하지 않는다**): `nginx/checkbook.conf`를 `/etc/nginx/sites-available/checkbook`로 복사하고 `sites-enabled`에 링크. `sites-enabled`의 `default`(`default_server`가 겹쳐 `nginx -t`가 실패함)와 임시 사이트 링크는 지운다. 사이트 설정이 include하는 `upstream.conf`는 6단계에서 생기므로 지금 `nginx -t`는 실패하는 게 정상이다. 이 상태에서는 nginx를 재시작할 수 없으니 6단계까지 이어서 한다. 첫 배포가 Nginx 전환 뒤에 실패하면 `upstream.conf`는 남겨 두고(그래야 nginx가 재시작된다) 다시 실행하면 된다. `nginx -t`나 reload 단계에서 실패했다면 원인을 고치고 `sudo nginx -t && sudo systemctl reload nginx`를 한 뒤 다시 실행한다.
5. `.env`에 DB 계정과 키를 채운다(아래 `.env`).
6. `sudo /bin/bash /opt/checkbook/app/deploy/deploy.sh ghcr.io/chanyong1027/checkbook@sha256:<digest>` → postgres가 처음 뜨고, 앱이 Flyway로 스키마를 만든 뒤 blue 칸이 뜬다.
7. 프런트 빌드 결과를 `/var/www/checkbook`에 둔다(root 소유, 파일 644·폴더 755).
8. 첫 데이터(공공도서관·전자도서관 목록)를 넣는다.
9. 백업 cron(`/etc/cron.d/checkbook-backup`):
   ```
   30 3 * * * root /bin/bash /opt/checkbook/app/deploy/backup.sh 2>&1 | systemd-cat -t checkbook-backup
   ```
   서버 시간대가 Asia/Seoul이라 03:30 KST다(앱의 03:00 정리 배치·04:00 추천 갱신과 겹치지 않는다). `/bin/bash`로 부르는 건 실행 권한이 빠져도 돌게 하려는 것.
10. 위 '래퍼 설치'를 한다(9번 `init`이 cron 경로를 `current`로 바꾼다).

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

## 로그
```
sudo journalctl -u checkbook-deploy-backend -f          # 배포(래퍼 + deploy.sh)
sudo journalctl CONTAINER_NAME=checkbook-app-blue-1 -f  # 앱(지금 칸은 deploy-state.env의 ACTIVE)
sudo journalctl CONTAINER_NAME=checkbook-postgres-1
sudo journalctl -t checkbook-backup                     # 백업
```
journald로 보내서 칸 컨테이너를 지워도 로그가 남는다. 보관량은 journald 기본값(파일시스템의 10%, 최대 4GB)을 따른다. `deploy.sh` 진행 기록은 `/var/log/checkbook-deploy.log`에도 남는다.

Nginx가 되돌리기까지 실패하면(`deploy.sh` 로그에 `모두 살려 둡니다`) 어느 칸도 지우지 않고 손으로 할 일을 출력한다. 그 순서대로 되돌린 뒤 `repair`한다.

## Nginx 설정 반영
`deploy/nginx/`의 파일은 자동 배포가 반영하지 않는다(배포 키에 Nginx 설정 쓰기 권한을 주지 않고, 설정은 하나라 잘못 들어가면 사이트 전체가 멈춘다). 바뀌면 서버에서 직접 한 줄로 반영한다.

```bash
sudo bash /opt/checkbook/app/deploy/apply-nginx.sh --diff   # 바뀔 내용만 보기(아무것도 안 바꿈)
sudo bash /opt/checkbook/app/deploy/apply-nginx.sh          # 반영
```

저장소 갱신(`git pull --ff-only`) → 서버 파일과 비교(같으면 끝) → 배포 잠금(`/run/checkbook/deploy.lock`, 배포가 Nginx를 전환하는 중이면 최대 120초 기다림) → `/var/backups/checkbook-nginx/<시각>/`에 백업(최근 10개) → 복사 → `nginx -t`(실패하면 되돌리고 reload하지 않음) → reload. `upstream.conf`는 `deploy.sh`가 관리하므로 건드리지 않는다. 처음 한 번은 스크립트가 서버 저장소에 없으므로 `sudo git -C /opt/checkbook/app pull --ff-only`를 먼저 한다.

## 관측: Grafana Alloy
서버에 systemd 서비스로 깔린 Alloy(deb 1.20.1, 자동 업데이트 없음)가 Grafana Cloud(무료, 도쿄 지역)로 보낸다.

| 보내는 것 | 어디서 | 비고 |
|---|---|---|
| 앱 지표 | `127.0.0.1:8081`(blue)·`8082`(green) `/actuator/prometheus`, 60초 | `job="checkbook-app"`, `slot` 라벨. 대기 칸은 꺼져 있어 `up=0`이 정상 → 앱 다운은 "칸 중 하나라도 up"으로 본다 |
| 서버 지표 | node_exporter 내장(디스크·메모리·CPU·네트워크) | `job="integrations/node_exporter"` |
| 로그 | journald 중 앱 컨테이너 전부 + 커널 err 이상 | `job="checkbook/journal"`, `container`·`slot`·`level` 라벨. 스택 트레이스는 한 항목으로 묶는다. 정보나루 `authKey=` 값은 가려서 보낸다. sshd·sudo·Nginx 등은 보내지 않는다 |

| 파일 | 서버 위치 | |
|---|---|---|
| `alloy/config.alloy` | `/etc/alloy/config.alloy` (root:alloy 640) | 주소·토큰 없음(`sys.env`로 읽음) |
| (`--secrets`가 씀) | `/etc/alloy/secrets.env` (root 600) | 주소 2개·사용자 ID 2개·토큰 |
| (`--secrets`가 씀) | `/etc/systemd/system/alloy.service.d/env.conf` | `EnvironmentFile=/etc/alloy/secrets.env` 한 줄. 값을 `Environment=`로 직접 쓰면 `systemctl show alloy`로 일반 사용자도 읽는다(설치 스크립트가 그렇게 만들었다) |

평소 반영(자동 배포에 넣지 않는다):
```bash
sudo bash /opt/checkbook/app/deploy/apply-alloy.sh --diff   # 바뀔 내용만(서버 파일의 토큰은 가려서 보임)
sudo bash /opt/checkbook/app/deploy/apply-alloy.sh          # 반영
```
저장소 갱신 → `alloy validate`(문법·속성 이름·구성 요소 참조. `fmt`는 문법만 본다) → 서버 파일과 비교(`--diff`는 여기서 끝) → `secrets.env`에 설정이 쓰는 값이 다 있는지 → `/var/backups/checkbook-alloy/<시각>/`에 백업(root 700, 최근 10개) → 복사 → `reset-failed` + `restart` → `http://127.0.0.1:12345/-/ready`를 30초 기다림 → 안 되면 되돌리고 다시 restart.
- `reset-failed`를 먼저 하는 이유: 유닛이 `Restart=always`(간격 100ms)라 곧 죽는 설정이면 몇 초 안에 시작 횟수 제한(10초에 5회)에 걸리고, 걸린 동안은 손으로 하는 restart도 거절된다. 직전에 몇 번 재시작했을 뿐인데 멀쩡한 설정이 "시작 실패"로 되돌려지는 것을 막는다.
- 준비 확인은 "설정을 불러왔다"까지만 본다. 토큰·주소가 틀려 보내기가 실패하는 것은 잡지 못한다 → 반영 뒤 `sudo journalctl -u alloy --since '-3 min' | grep -iE 'error|401|403'`가 비어 있는지와 Grafana Explore에서 `up{job="checkbook-app"}`, `{job="checkbook/journal"}`로 확인한다.
- Alloy 자체 로그: `sudo journalctl -u alloy -f`. 구성 요소 상태: `curl -s http://127.0.0.1:12345/api/v0/web/components | head`(서버 안에서만 열림).

처음 한 번(설치 스크립트 설정 → 저장소 설정, 10-19):
1. Grafana 포털 → Security → Access Policies에서 정책 `checkbook-alloy`(scope `metrics:write`·`logs:write`만)를 만들고 토큰을 발급한다. 설치 마법사의 토큰(정책 `stack-1881956-quickstart`)은 한동안 644 파일과 `Environment=`에 있어 서버의 다른 사용자가 읽을 수 있었으므로 바꾼다.
2. `sudo git -C /opt/checkbook/app pull --ff-only`
3. `sudo bash /opt/checkbook/app/deploy/apply-alloy.sh --diff` — 바뀔 내용을 보고, 지울 줄(`-`)에 있는 remote_write·Loki 주소와 사용자 ID를 옮겨 적는다(토큰은 `<TOKEN>`으로 가려진다). 원본 파일을 `cat`하지 않는다(평문 토큰이 터미널에 남는다).
4. `sudo bash /opt/checkbook/app/deploy/apply-alloy.sh --secrets` — 3의 주소·ID와 1의 토큰(입력이 화면에 안 보인다). Alloy는 아직 재시작하지 않는다.
5. `sudo bash /opt/checkbook/app/deploy/apply-alloy.sh --no-pull`로 반영하고, 위의 확인 명령으로 보내기 성공을 확인한다. 저널 읽기 위치는 구성 요소 이름이 바뀌어 처음부터 다시 잡히므로, 이때 최근 24시간치 앱 로그를 한 번 더 보낸다(새 라벨이라 중복으로 보인다, 한 번뿐).
6. 새 토큰으로 들어오는 것을 확인한 **뒤에** 포털에서 quickstart 토큰을 삭제하고, 5에서 만든 백업(`/var/backups/checkbook-alloy/<시각>/`, 설치 스크립트 원본 = 평문 옛 토큰)을 지운다. 지운 토큰을 쓰는 설정으로 되돌리면 Alloy는 떠도 401로 아무것도 못 보낸다.

토큰을 바꿀 때는 `--secrets` 뒤 `--restart`(reset-failed + 재시작 + 준비 확인만 한다. 옵션 없이 실행하면 설정이 같아 "변경 없음"으로 끝나고 재시작하지 않는다).

## 알림: Grafana 규칙
규칙은 `grafana/rules.py` 한 곳에 있고, 내 PC(WSL)에서 provisioning API로 Grafana Cloud에 반영한다(서버 작업 아님). 근거·숫자는 결정 카드 `notes/decisions/2026-10-19-PR-B-알림규칙-결정카드.md`.

| 등급 | 연락처(Slack) | 규칙 |
|---|---|---|
| 즉시 | `slack-urgent`(#checkbook-urgent), 재알림 4시간 | 앱 지표 끊김, 서버 5xx 소진율, 예산 차단선, YES24 RATE_002, 디스크 10% 미만·4시간 안에 참 |
| 낮 | `slack-info`(#checkbook-info), 재알림 24시간 | 섹션 실패·외부 호출 느림 소진율, 5xx 1건, ERROR 로그, 서킷, 예산 80%, featured 실패·만료·받은 적 없음, 전자도서관 업체 구조 변경, 디스크 하루 안에 참, 메모리 90%, 알림이 보는 지표 사라짐 |

| 파일 | 하는 일 |
|---|---|
| `grafana/thresholds.py` | 비율 규칙의 최소 건수 계산(탐침 기준선 + 오탐 예산 → 이항분포). `python3 deploy/grafana/thresholds.py`로 근거 표 |
| `grafana/rules.py` | 규칙 원본. 같은 식으로 Grafana 규칙 JSON과 promtool 규칙 파일을 만든다(`--list`로 목록) |
| `grafana/apply-alerts.py` | 반영: 차이 보기 → 원본에서 빠졌거나 묶음을 옮긴 규칙 삭제 → 묶음 PUT → 다시 읽어 같은지 확인 |
| `grafana/tests/` | `python3 -m unittest discover -s deploy/grafana/tests`(생성기·반영 스크립트, 가짜 Grafana), `bash deploy/grafana/tests/run-promtool.sh`(식을 합성 시리즈로, Docker) |

평소 반영:
```bash
python3 deploy/grafana/apply-alerts.py --diff   # 지금 Grafana와 원본의 차이(읽기만)
python3 deploy/grafana/apply-alerts.py --yes    # 반영 + 확인. --yes가 없으면 차이만 보여 주고 멈춘다
```
- API로 넣은 규칙은 UI에서 고칠 수 없다. 고칠 때는 `rules.py`를 고치고 시험 두 가지를 돌린 뒤 다시 반영한다.
- 질의가 깨지면(문법·데이터 소스) 규칙이 Error 상태가 되어 알림으로 온다. 앱이 기동 때 만드는 지표의 이름을 바꾸면 "알림이 보는 지표가 사라짐"이 15분 뒤 그 이름과 함께 온다. 첫 호출 때 생기는 지표(`http_client_requests_*`, `http_server_requests_*`)는 평소에도 없을 수 있어 이 감시에서 빠진다 — 이 이름을 바꿀 때는 규칙도 같이 고친다.
- 처음 반영한 뒤 `--diff`를 한 번 더 돌려 "변경 없음"인지 본다. 비교에서 빼는 Grafana 쪽 표기 차이를 가짜 서버로만 시험해서, 실제 응답에서 늘 차이로 보이면(반영 뒤 확인 실패가 반복되면) `normalize()`를 맞춘다.
- 앱 지표를 새로 쓰는 규칙은 앱이 그 지표를 내보내는 배포가 먼저다(안 그러면 지표 사라짐 알림).

처음 한 번:
1. Grafana → Alerting에서 폴더 `checkbook`을 만들고 주소의 uid를 적는다. Connections → Data sources에서 Prometheus(`grafanacloud-…-prom`)·Loki(`grafanacloud-…-logs`)의 uid를 적는다(주소 `/datasources/edit/<uid>`).
2. Administration → Service accounts에서 `checkbook-alerts`를 만들고 역할은 `fixed:alerting.provisioning:writer` 하나만(No basic role), 토큰 만료 2027-10-20. 이 권한은 규칙뿐 아니라 연락처·알림 정책도 바꿀 수 있다(지표·로그는 못 읽는다).
3. 저장소 `.env`(git에 안 올라감)에 `GRAFANA_URL`·`GRAFANA_ALERTS_TOKEN`·`GRAFANA_ALERTS_FOLDER_UID`·`GRAFANA_PROM_UID`·`GRAFANA_LOKI_UID`.
4. `--diff` → `--yes` → `--fire-test`(두 채널로 시험 알림 2분 → 시험 규칙 삭제)로 Slack까지 확인.

## 요청 제한 dry run
`checkbook.conf`의 `limit_req_dry_run`은 10-18에 `off`로 바꿨다(실제로 막는다). 근거: 사용자가 없을 때는 "실제 사용자가 걸리는지" 관찰할 수 없어 숫자로 판단했다. 사람이 책 한 권을 볼 때는 짧은 시간에 3~4회 요청이라 `/api/` 5r/s(burst 20)에 걸리지 않고, 10-18 access.log 바깥 요청(거의 전부 스캐너)에서도 `/api/`를 IP·초당 5회 넘게 보낸 경우가 0건이었다. 하루 총량과 공급자 예산은 앱 안 요청 제한(`application.yaml`의 `rate-limit`)이 지킨다.

- 막힌 요청은 `/var/log/nginx/error.log`에 `limiting requests, excess: ... by zone "..."`(error 레벨)로, access.log에는 429로 남는다.
- 사람이 막힌다면 dry run을 다시 켜지 말고 한도 숫자를 늘린다(dry run을 켜면 스캐너·폭주도 전부 통과한다). 한도를 더 빡빡하게 바꿀 때만 그 규칙에 잠시 `on`으로 관찰한다.
- 사이트 설정 반영은 아래 'Nginx 설정 반영' 절의 스크립트로 한다.

## 백업 복원
앱 칸을 먼저 멈춘다(연결이 남아 있으면 `--clean`이 잠금을 기다리거나 일부만 복원된다). 서비스가 잠깐 멈춘다.
```
sudo docker compose --project-directory /opt/checkbook/current/deploy --env-file /opt/checkbook/.env stop app-blue app-green
sudo docker compose --project-directory /opt/checkbook/current/deploy --env-file /opt/checkbook/.env exec -T postgres \
  sh -c 'pg_restore -U "$POSTGRES_USER" -d checkbook --clean --if-exists --single-transaction' < /var/backups/checkbook/checkbook-<시각>.dump
```
그다음 서비스 중이던 칸의 digest로 래퍼 `rollback`을 한다(`sudo /usr/local/sbin/checkbook-deploy backend rollback <장부의 DIGEST>`).

## Cloudflare 대역 갱신
대역은 https://www.cloudflare.com/ips-v4 , https://www.cloudflare.com/ips-v6 에 있다. 바뀌면 `nginx/cloudflare-realip.conf`와 OCI 보안 목록(80·443 수신)을 같이 고친다.

## 로컬에서 검증할 때
journald가 없는 Docker Desktop에서는 `CHECKBOOK_LOG_DRIVER=json-file`로 돌린다. `deploy.sh`의 경로·명령은 `CHECKBOOK_*` 환경 변수로 바꿀 수 있다(스크립트 위쪽 참고). 래퍼는 환경 변수를 읽지 않는다. 저장소 주소만 root 소유 `/etc/checkbook/deploy-wrapper.conf`(`BACKEND_REPO=`, `FRONTEND_REPO=`)로 바꿀 수 있고, 운영 서버에는 이 파일을 두지 않는다.
