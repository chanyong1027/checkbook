# 도서 식별 공급자 비교 (알라딘 → YES24)

알라딘 OpenAPI가 2026-10-30에 종료되어 도서 식별을 YES24로 옮긴다. 종료 전에 같은 입력으로 두 공급자를 비교해 두려고 만든 스크립트다.

## 실제 서비스 흐름과 맞춘 비교

사용자는 카카오 후보 목록에서 책을 고르고, 그때 정해진 ISBN으로 상세 조회(`/api/search`)를 한다. 그래서 비교도 **ISBN 조회** 중심으로 한다. 키워드 식별은 보조로만 본다.

## 실행 순서

저장소 루트에서 실행한다. 표준 라이브러리만 쓰고, 키는 루트 `.env`에서 읽는다.

| 순서 | 스크립트 | 하는 일 | 필요한 키 |
|---|---|---|---|
| 1 | `collect_kakao_seed_isbns.py` | `kakao-seed-keywords.csv`의 검색어로 카카오 후보를 받아 상위 ISBN 추출 → `isbns-from-kakao.csv` | `KAKAO_BOOK_REST_API_KEY` |
| 2 | `build_isbn_set.py` | 카카오 묶음 + 밀리 PoC 책 + 정보나루 인기 대출 + `tricky-isbns.csv`를 합쳐 `isbns.csv` | 카카오, `DATANARU_AUTH_KEY` |
| 3 | `collect_aladin.py` | 앱의 `AladinClient`와 같은 요청으로 알라딘 수집 (10/30 이전에만 가능) | `ALADIN_TTB_KEY` |
| 4 | `collect_yes24.py` | 같은 입력으로 YES24 수집 + 호출 한도·오류 형식 탐침 | `YES24_KEY` |

```bash
python3 scripts/identification-compare/collect_kakao_seed_isbns.py
python3 scripts/identification-compare/build_isbn_set.py
python3 scripts/identification-compare/collect_aladin.py --dry-run   # 입력 검증만
python3 scripts/identification-compare/collect_aladin.py
python3 scripts/identification-compare/collect_yes24.py --probe-only # 한도·형식 확인만
python3 scripts/identification-compare/collect_yes24.py
```

모든 스크립트는 호출 사이에 1초씩 쉰다. 카카오 시드 수집과 YES24 수집은 인증 실패(401/403)나 호출 한도 초과(429)에서 바로 멈추고, 알라딘 수집은 인증 실패나 처음 3건이 모두 오류일 때 멈춘다(알라딘은 키 오류를 HTTP 200 + 오류 코드로 줄 수 있어서). `build_isbn_set.py`는 실패한 질의를 건너뛰고 끝에 결과 수를 출력한다.

## 데이터 취급

- 이 폴더에는 **검색어·ISBN·손으로 쓴 짧은 메모**만 둔다(커밋 대상). 공급자 응답을 옮겨 적지 않는다.
- 공급자 응답 원문과 분석 결과는 `notes/incidents/aladin-sunset/` 아래(gitignore)에만 저장한다. 알라딘·YES24 모두 약관상 데이터 저장·축적에 제한이 있기 때문이다.
- 실제 외부 API로 부하를 주지 않는다. 2026-10-13 전체 실행의 호출 수는 카카오 약 40회(하루 30,000), 알라딘 약 165회(5,000), YES24 약 140회(20,000, 응답 헤더로 확인), 정보나루 1~2회로 각 하루 한도의 5% 미만이었다.

## 비교 기준 셋은 고정한다

`isbns.csv`는 알라딘 수집에 쓴 입력이다. 알라딘은 10/30 이후 다시 수집할 수 없고, 정보나루 인기 대출 목록은 날마다 바뀐다. 그래서 `build_isbn_set.py`는 기존 `isbns.csv`가 있으면 덮어쓰지 않는다(`--force`로만 가능). 공급자 간 결과는 `id`가 아니라 `isbn13`으로 맞춰 비교한다.

요약 표를 다시 만들 때는 API를 부르지 않고 저장된 원문으로 만든다.

```bash
python3 scripts/identification-compare/collect_aladin.py --from-raw notes/incidents/aladin-sunset/raw/aladin-<시각>.jsonl
python3 scripts/identification-compare/collect_yes24.py  --from-raw notes/incidents/aladin-sunset/raw/yes24-<시각>.jsonl
```
