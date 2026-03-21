#!/bin/bash
set -e

DOCKERHUB_IMAGE="도커허브유저명/checkbook:latest"
REGION="ap-northeast-2"

echo "=== 배포 시작: $(date) ==="

# Docker Hub에서 최신 이미지 Pull
echo "이미지 Pull 중..."
docker pull "$DOCKERHUB_IMAGE"

# SSM에서 환경변수 가져오기
echo "환경변수 로드 중..."
ssm() {
  aws ssm get-parameter \
    --name "$1" \
    --with-decryption \
    --query "Parameter.Value" \
    --output text \
    --region "$REGION"
}

# 임시 env 파일 생성 (권한 600 = 소유자만 읽기/쓰기)
ENV_FILE=$(mktemp)
chmod 600 "$ENV_FILE"

cat > "$ENV_FILE" << EOF
DB_URL=$(ssm /checkbook/prod/db-url)
DB_USERNAME=$(ssm /checkbook/prod/db-username)
DB_PASSWORD=$(ssm /checkbook/prod/db-password)
JPA_DDL_AUTO=$(ssm /checkbook/prod/jpa-ddl-auto)
ALADIN_TTB_KEY=$(ssm /checkbook/prod/aladin-ttb-key)
KAKAO_BOOK_REST_API_KEY=$(ssm /checkbook/prod/kakao-book-rest-api-key)
DATANARU_AUTH_KEY=$(ssm /checkbook/prod/datanaru-auth-key)
CORS_ALLOWED_ORIGINS=$(ssm /checkbook/prod/cors-allowed-origins)
EOF

# 기존 컨테이너 중지 및 삭제 (없으면 무시)
echo "기존 컨테이너 정리 중..."
docker stop checkbook 2>/dev/null || true
docker rm checkbook 2>/dev/null || true

# 새 컨테이너 시작
echo "컨테이너 시작 중..."
docker run -d \
  --name checkbook \
  --env-file "$ENV_FILE" \
  -p 80:8080 \
  --restart unless-stopped \
  "$DOCKERHUB_IMAGE"

# 임시 env 파일 삭제 (보안)
rm "$ENV_FILE"

# 헬스 체크 (10초 대기 후 확인)
echo "헬스 체크 대기 중..."
sleep 10
if curl -sf http://localhost/actuator/health > /dev/null; then
  echo "=== 배포 완료: $(date) ==="
else
  echo "=== 경고: 헬스 체크 실패. 로그 확인 필요 ==="
  docker logs --tail 50 checkbook
  exit 1
fi
