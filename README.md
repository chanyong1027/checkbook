# CheckBook - 도서 통합 검색 서비스

> 읽고 싶은 책의 도서관 소장 현황, 중고 가격, 전자도서관 대출 가능 여부를 한 번에 확인할 수 있는 서비스입니다. 여러 사이트를 따로 확인해야 하는 번거로움을 없애고자 개발했습니다.
>
> https://checkbook.kr

<br>

## 서비스 화면

| 메인 | 검색 결과 |
|:---:|:---:|
| <img width="2878" height="1520" alt="Image" src="https://github.com/user-attachments/assets/c83f3002-2b63-47cd-9fe3-c382bb798584" /> | <img width="2879" height="1534" alt="Image" src="https://github.com/user-attachments/assets/3b26390d-d7f5-460d-b7f7-21f5f4fae2f0" /> |

| 상세 - 공공도서관 | 상세 - 중고/전자도서관 |
|:---:|:---:|
| <img width="2879" height="1534" alt="Image" src="https://github.com/user-attachments/assets/b4dcd2f9-2142-4635-8da7-a1e1a4f5cb2b" /> | <img width="2879" height="1539" alt="Image" src="https://github.com/user-attachments/assets/6e075828-5073-444f-9110-9bccff34d4c4" /> |

<br>

## 주요 기능

- **도서 식별** — 제목, 저자, ISBN으로 검색하면 알라딘/카카오 API를 통해 정확한 도서를 식별
- **공공도서관 소장/대출 현황** — 사용자 위치 기반으로 가까운 공공도서관 20곳의 소장 여부와 대출 가능 여부를 실시간 조회
- **중고 도서 가격 비교** — 알라딘 중고 3개 채널(개인판매, 온라인, 매장)의 최저가를 한눈에 비교
- **전자도서관 대출 가능 여부** — 선택한 전자도서관에서 해당 도서의 대출 가능 여부를 실시간 검색

<br>

## Tech Stack

### Frontend

![TypeScript](https://img.shields.io/badge/typescript-%233178C6.svg?style=for-the-badge&logo=typescript&logoColor=white)
![React](https://img.shields.io/badge/react-%23333333.svg?style=for-the-badge&logo=react&logoColor=%2361DAFB)
![Tailwind CSS](https://img.shields.io/badge/tailwind%20css-%2306B6D4.svg?style=for-the-badge&logo=tailwindcss&logoColor=white)
![Vite](https://img.shields.io/badge/vite-%23646CFF.svg?style=for-the-badge&logo=vite&logoColor=white)

### Backend

![Java](https://img.shields.io/badge/java_17-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/spring%20boot_3.5-%236DB33F.svg?style=for-the-badge&logo=springboot&logoColor=white)
![JPA](https://img.shields.io/badge/jpa-%2359666C.svg?style=for-the-badge&logo=hibernate&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/postgresql-%234169E1.svg?style=for-the-badge&logo=postgresql&logoColor=white)
![Flyway](https://img.shields.io/badge/flyway-%23CC0200.svg?style=for-the-badge&logo=flyway&logoColor=white)

### Infra

![Docker](https://img.shields.io/badge/docker-%230db7ed.svg?style=for-the-badge&logo=docker&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/github%20actions-%232088FF.svg?style=for-the-badge&logo=githubactions&logoColor=white)
![AWS EC2](https://img.shields.io/badge/ec2-%23FF9900.svg?style=for-the-badge&logo=amazonec2&logoColor=white)
![AWS RDS](https://img.shields.io/badge/rds-%23527FFF.svg?style=for-the-badge&logo=amazonrds&logoColor=white)
![AWS S3](https://img.shields.io/badge/s3-%23569A31.svg?style=for-the-badge&logo=amazons3&logoColor=white)
![CloudFront](https://img.shields.io/badge/cloudfront-%238C4FFF.svg?style=for-the-badge&logo=amazonaws&logoColor=white)

<br>

## 아키텍처

### 시스템 구조

<img width="1568" height="498" alt="Image" src="https://github.com/user-attachments/assets/38d5f6b6-6d71-4bcc-bdc5-6d126414cef9" />

### 검색 처리 흐름

하나의 검색 요청이 들어오면 여러 외부 소스를 **병렬로** 호출하여 2.8초 내에 응답합니다.

<img width="1568" height="542" alt="Image" src="https://github.com/user-attachments/assets/2602a181-daa2-48da-bd2b-f2f0a9a629c9" />

### 배포 파이프라인

<img width="1568" height="280" alt="Image" src="https://github.com/user-attachments/assets/749fdf63-4bd2-49b0-be40-5535ef8dd2f4" />

<br>

## 설계 결정

| 항목 | 결정 | 이유 |
|---|---|---|
| 공공도서관 조회 | 실시간 API 호출 | 대출 가능 여부는 실시간 상태이므로 캐싱 불가 |
| 전자도서관 조회 | 실시간 크롤링 | 공식 API 미제공, 대출 상태도 실시간 확인 필요 |
| 검색 타임아웃 | 2.8초 데드라인 | 응답 지연 시 도착한 결과까지만 반환 (graceful degradation) |
| 외부 API 장애 대응 | Resilience4j Circuit Breaker | 도서관정보나루 API 장애 시 연쇄 실패 방지 |
| DB 마이그레이션 | Flyway | JPA ddl-auto 대신 SQL 기반 버전 관리 |
| 프론트/백 분리 배포 | S3+CloudFront / EC2+Docker | 정적 자원과 API 서버의 스케일링 독립 |

<br>

## 패키지 구조

```
src/main/java/com/checkbook/
├── client/                          # 외부 API 클라이언트
│   ├── aladin/                      #   알라딘 (도서 식별, 중고가격)
│   ├── datanaru/                    #   도서관정보나루 (공공도서관 소장/대출)
│   └── kakao/                       #   카카오 (도서 식별 보조)
├── search/                          # 통합 검색 (오케스트레이터)
│   ├── controller/
│   ├── service/
│   └── dto/
├── publiclibrary/                   # 공공도서관 도메인
│   ├── domain/
│   ├── repository/
│   ├── service/
│   └── snapshot/                    #   소장/대출 스냅샷 캐시
├── elibrary/                        # 전자도서관 도메인
│   ├── client/                      #   교보문고, 북큐브 크롤링 클라이언트
│   ├── domain/
│   ├── service/
│   └── controller/
├── aladinstore/                     # 알라딘 중고매장 도메인
│   ├── domain/
│   └── repository/
└── common/                          # 공통 (예외, 유틸, 설정)
```

<br>

## 테스트

28개 테스트 클래스, MockWebServer 기반 외부 API 테스트 포함.

```bash
./gradlew test
```

<br>

## 마주했던 문제와 결정

- [전자도서관 실시간 크롤링 vs 사전 캐싱 비교](notes/decisions/2026-04-15-전자도서관-크롤링-vs-사전캐싱.md)
- [AladinBookService 추출 — 서비스 구조 설계 결정](notes/decisions/2026-04-08-알라딘-서비스-추출-구조-결정.md)
