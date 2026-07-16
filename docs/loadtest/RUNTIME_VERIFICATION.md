# Phase 0–1 런타임 검증 결과 (실측)

> 실측 일시: 2026-07-09 (KST). 환경: MacBook M2 16GB, Docker Compose(로컬 단일 노드).
> 백엔드는 `app/backend/Dockerfile`의 `eclipse-temurin:17-jre-alpine`가 arm64 매니페스트 미매치라
> **linux/amd64 에뮬레이션**으로 빌드·기동(팀 CI는 amd64). 서비스: mysql 8.0, redis 7-alpine,
> backend, prometheus v2.53.0, grafana 11.1.0. 시드: users 57 / zones 4 / batches 2 / batch_task_items 345.

## Phase 0 — 관측성 파이프라인

| 항목 | 결과 | 근거 |
|---|---|---|
| `/actuator/prometheus` 노출 | OK — `application="lookie"` 태그 부착 메트릭 다수 | `curl :8080/actuator/prometheus` |
| `http.server.requests` 히스토그램 | OK — `http_server_requests_seconds_bucket` **138개 le 버킷** | 퍼센타일 히스토그램 설정 반영 |
| Prometheus 스크레이프 타겟 | **UP** — `up{job="lookie-backend"} = 1` | `:9090/api/v1/query?up{...}` |
| Prometheus PromQL 조회 | OK — `sum(jvm_memory_used_bytes{application="lookie"})` = 약 169MB | `:9090/api/v1/query` |
| Grafana 헬스 | OK — 11.1.0, database ok | `:3000/api/health` |
| Grafana 데이터소스 자동 프로비저닝 | OK — `Prometheus`(`http://prometheus:9090`, isDefault, readOnly) | `:3000/api/datasources` |
| Grafana JVM 대시보드(4701) | **import 성공** — `/d/bfrjr7mbv1ipsc/jvm-micrometer` | `POST /api/dashboards/import` |

> 헤드리스 환경이라 PNG 스크린샷은 미캡처(Grafana image-renderer 플러그인 부재). 대시보드는 위 URL로
> 실제 존재하며 `http://localhost:3000` (admin/admin)에서 열람 가능. 스크린샷이 필요하면 UI에서 저장.

## Phase 1 — 위생 클린업 런타임 검증

| 클린업 | 검증 방법 | 결과 |
|---|---|---|
| 3. `:progress` EXPIRE 24h | 관제 API 호출로 lazy init 후 `redis-cli TTL lookie:control:zone:{1..4}:progress` | **TTL=86400**(4개 구역 모두). 변경 전에는 TTL 미설정(-1). |
| 5. 구역 임계값 외부화 | `control.zone-status-threshold-minutes` 기본 30 → 250로 재기동 후 `/api/control/zones` 상태 비교 | threshold=30: zone 2/3/4 = **STABLE**(gap≈223–229) → threshold=250: 동일 zone **NORMAL**로 전이, zone 1은 gap<0이라 CRITICAL 유지. 이후 30으로 복구 확인. |
| 1. 레거시 ISSUE 참조 제거 | 정적(`grep '"ISSUE"'` = ISSUE_PENDING만) + 컴파일 | dead branch 제거, 컴파일 OK |
| 2. completeItemManual 정리 | 정적(로직 모순 해소) + 컴파일 | IN_PROGRESS→DONE 도달 가능하게 교정 |
| 4. AI 클라이언트 타임아웃 | 정적(`aiRestTemplate` Bean 주입) + 컴파일 | connect 3s/read 5s 외부화. 런타임 타임아웃 발화는 AI 서버 지연 필요라 미유발. |

## Baseline

- 7개 GET 엔드포인트 각 100회 순차, **전부 100% 2xx**. 결과: `docs/loadtest/BASELINE_METRICS.md`, 원시: `baseline_raw/*.jsonl`.
- 로그인: 시드 ADMIN `010-9000-0001`에 테스트 비번(bcrypt) 주입 후 사용(로컬 dev DB 한정, 커밋 대상 아님).

## 비고 / 한계

- amd64 에뮬레이션이라 응답시간 절대값은 네이티브보다 큼 — 상대 비교·경향 파악용.
- 스택은 관측성 검증에 필요한 최소 서비스(mysql/redis/backend/prometheus/grafana)만 기동(airflow/nginx/ai-server 제외).
- 헤드리스라 Grafana PNG 미첨부(대시보드는 import 완료·URL 존재).
