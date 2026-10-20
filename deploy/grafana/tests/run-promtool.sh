#!/usr/bin/env bash
# rules.py가 만든 식을 promtool로 합성 시리즈에 돌려 본다(Docker 필요, 네트워크·비밀값 불필요).
# 실행: bash deploy/grafana/tests/run-promtool.sh
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
image=${PROMETHEUS_IMAGE:-prom/prometheus:v3.14.0}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

python3 "$here/../rules.py" --promtool "$work/rules.json"
cp "$here/rules_test.yaml" "$work/"
# 컨테이너는 nobody로 돌아 읽기만 하면 되도록 권한을 연다
chmod -R a+rX "$work"
docker run --rm -v "$work:/w:ro" --entrypoint promtool "$image" test rules /w/rules_test.yaml
