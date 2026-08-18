# petcoupon-backend

반려동물 케어 플랫폼 백엔드 — 선착순 쿠폰 발급 시스템

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.1.0 |
| Database | MySQL 8.0 |
| Cache / 재고 제어 | Redis 7.2 |
| Message Queue | Kafka 3.7.0 (KRaft) |
| Build | Gradle |

## 실행 방법

MySQL · Redis · Kafka가 로컬에 기동되어 있어야 한다.

```bash
./gradlew bootRun
```

## 협업 규칙

작업 순서: Issue 생성 → `dev`에서 브랜치 생성 → 작업·커밋 → Push → `dev` 대상 PR → 리뷰 → Merge → 브랜치 삭제

| 구분 | 형식 | 예시 |
|---|---|---|
| 브랜치 | `{type}/{이슈번호}-{작업내용}` | `feat/12-coupon-issue-api` |
| 이슈 제목 | `[TYPE] 작업 내용` | `[FEAT] 쿠폰 발급 API 구현` |
| 커밋 | `type: 구체적인 변경 내용` | `feat: 쿠폰 발급 API 구현` |
| PR 제목 | `[TYPE] 작업 내용 (#이슈번호)` | `[FEAT] 쿠폰 발급 API 구현 (#12)` |

type: `feat` 기능 추가 / `fix` 오류 수정 / `test` 테스트 코드·부하테스트 /
`docs` 문서 / `refactor` 기능 변경 없는 구조 개선 / `chore` 설정·환경·의존성
