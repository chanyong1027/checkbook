# 프런트 배포 산출물: 빌드 결과(dist)만 담는다. 실행하지 않는 이미지라 OS가 없다(FROM scratch, Trivy 관문 없음).
# 빌드 문맥은 frontend/dist다(frontend/를 문맥으로 쓰면 node_modules까지 보낸다).
# 서버 래퍼가 docker cp로 /site를 꺼내 구조를 검사한 뒤 릴리스 폴더로 옮긴다(PR-O2 결정 카드 5)
#   docker build -f frontend/artifact.Dockerfile frontend/dist
FROM scratch
COPY . /site
