#!/bin/bash
# WireMock 꼬리 지연 확장(tail-delay) jar를 만든다. Gradle을 쓰지 않고 JDK 컨테이너의 javac·jar로 빌드한다.
# WireMock 클래스는 측정에 쓰는 wiremock/wiremock:3.13.0 이미지 안의 standalone jar를 그대로 클래스패스로 쓴다
# (외부 저장소에서 받지 않음, 실행 버전과 컴파일 버전이 어긋나지 않음).
# 결과: scripts/loadtest/wiremock-ext/build/tail-delay.jar (git 제외, compose가 /var/wiremock/extensions로 싣는다)
# 사용: scripts/loadtest/wiremock-ext/build.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
WIREMOCK_IMAGE=wiremock/wiremock:3.13.0
JDK_IMAGE=eclipse-temurin:21-jdk
mkdir -p "$HERE/build"

# 1) 이미지에서 standalone jar를 꺼낸다(컨테이너는 만들고 바로 지운다)
cid=$(docker create "$WIREMOCK_IMAGE")
trap 'docker rm -f "$cid" > /dev/null' EXIT
docker cp "$cid:/var/wiremock/lib/wiremock-standalone.jar" "$HERE/build/wiremock-standalone.jar"

# 2) 컴파일하고 jar로 묶는다. wiremock 3.13.0 이미지의 JRE가 11(Temurin 11.0.24)이라 --release 11
docker run --rm -u "$(id -u):$(id -g)" -v "$HERE:/src" -w /src "$JDK_IMAGE" sh -c '
  set -e
  rm -rf build/classes && mkdir -p build/classes
  javac --release 11 -cp build/wiremock-standalone.jar -d build/classes TailDelayTransformer.java
  jar --create --file build/tail-delay.jar -C build/classes .
'
# standalone jar는 클래스패스용이라 extensions 폴더에 남기지 않는다(같은 클래스가 두 번 실림)
rm -f "$HERE/build/wiremock-standalone.jar"
echo "built: $HERE/build/tail-delay.jar"
