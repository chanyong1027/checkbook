#!/usr/bin/env bash
# 외부 API 레이턴시 분포 실측 — EC2에서 실행 (운영과 동일한 네트워크 경로)
# API 키는 EC2 인스턴스 역할로 SSM Parameter Store에서 읽음 (deploy.sh와 동일 방식)
# 사용: bash measure-external-latency.sh [회수(기본 100)]
#
# 목적 (AWS Builders' Library 방법론):
#   허용 false-timeout 비율(폴백이 있으므로 1% = p99)에 해당하는 백분위를 실측해
#   타임아웃 수치의 근거로 삼고, WireMock 지연 분포 파라미터로도 사용한다.
set -u
N="${1:-100}"
REGION=ap-northeast-2
ssmp() {
  aws ssm get-parameter --name "$1" --with-decryption \
    --query Parameter.Value --output text --region "$REGION"
}
ALADIN_KEY=$(ssmp /checkbook/prod/aladin-ttb-key)
DATANARU_KEY=$(ssmp /checkbook/prod/datanaru-auth-key)
KAKAO_KEY=$(ssmp /checkbook/prod/kakao-book-rest-api-key)
OUT="/tmp/external-latency-$(date +%Y%m%d-%H%M).csv"
echo "api,run,total_ms" > "$OUT"

measure() { # $1=api명 $2=요청간격(초) $3=회수, 이후 curl 인자
  local api=$1 gap=$2 n=$3; shift 3
  local ok=0 fail=0
  echo "측정 중: $api (${n}회, ${gap}s 간격)..."
  for i in $(seq 1 "$n"); do
    # 주의: -w 출력은 --fail과 무관하게 실패(4xx/연결오류)에도 찍히므로
    # 반드시 curl 종료 코드로 게이트해야 실패 표본이 백분위를 오염시키지 않는다
    if t=$(curl -sf -o /dev/null -w "%{time_total}" --max-time 10 "$@"); then
      echo "$api,$i,$(awk "BEGIN{printf \"%.0f\", $t*1000}")" >> "$OUT"
      ok=$((ok+1))
    else
      fail=$((fail+1))
    fi
    sleep "$gap"
  done
  echo "  → 성공 $ok / 실패 $fail"
  [ "$fail" -gt $((n/10)) ] && echo "  ⚠️ 실패율 10% 초과 — 키/엔드포인트 확인 후 이 세션 폐기 검토"
}

# 정보나루 bookExist — 권장 "초당 2회 미만" 준수 (0.7s 간격). libCode는 실존 코드로 교체 가능
measure datanaru-bookExist 0.7 "$N" \
  "https://data4library.kr/api/bookExist?authKey=${DATANARU_KEY}&libCode=111003&isbn13=9788936434120&format=json"
# 알라딘 ItemLookUp (식별 핫패스 — identification-timeout 500ms의 근거가 될 분포)
measure aladin-lookup 0.5 "$N" \
  "https://www.aladin.co.kr/ttb/api/ItemLookUp.aspx?ttbkey=${ALADIN_KEY}&ItemId=9788936434120&ItemIdType=ISBN13&output=js&Version=20131101"
# 카카오 도서 검색
measure kakao-search 0.5 "$N" -H "Authorization: KakaoAK ${KAKAO_KEY}" \
  "https://dapi.kakao.com/v3/search/book?query=%EC%B1%84%EC%8B%9D%EC%A3%BC%EC%9D%98%EC%9E%90&size=5"
# 밀리 검색 — 비공개 API라 절반 회수 + 간격 2s (남용 회피)
measure millie-search 2 "$((N/2))" \
  -H "User-Agent: CheckBook/1.0 (+https://github.com/chanyong1027/checkbook)" \
  "https://live-api.millie.co.kr/v3/search/total?searchType=total&keyword=%EC%B1%84%EC%8B%9D&contentlimitCount=20&postlimitCount=0&librarylimitCount=0&startPage=1"

echo "원본: $OUT"
echo "=== 백분위 요약 (ms) ==="
sort -t, -k1,1 -k3,3n "$OUT" | awk -F, 'NR>1 {
  cnt[$1]++; vals[$1","cnt[$1]]=$3
} END {
  for (k in cnt) {
    n=cnt[k]
    i50=int(n*0.50); if (i50 < 1) i50=1
    i95=int(n*0.95); if (i95 < 1) i95=1
    i99=int(n*0.99); if (i99 < 1) i99=1
    printf "%s: n=%d p50=%s p95=%s p99=%s\n", k, n, vals[k","i50], vals[k","i95], vals[k","i99]
  }
}'
