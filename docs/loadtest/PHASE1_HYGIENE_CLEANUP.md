# Phase 1 — 사전 위생 클린업 + Baseline 수집

> 브랜치: `chore/pre-experiment-hygiene` (base: `experiment/observability-setup` ← `develop b8bf1e2`)
> 이 브랜치는 이후 모든 실험 브랜치(`experiment/exp-a|b|c-*`, `fix/*`, `docs/loadtest-reports`)의 base가 된다.
> 컴파일 검증: `sh ./mvnw compile` → EXIT=0 (전 클린업 반영 후).

## Phase 0 관측성 인프라 (동일 PR에 포함)

`feat: Prometheus + Grafana + Actuator/Micrometer 관측성 인프라 세팅` — `12f4887`

- `pom.xml`: `spring-boot-starter-actuator`, `micrometer-registry-prometheus`.
- `application(.properties, -docker)`: `/actuator/prometheus` 노출 + `http.server.requests` 히스토그램(p50/p95/p99).
  - Spring Boot 3.2.2 기준으로 `management.endpoint.*.access=unrestricted`(3.4+ 문법)는 사용하지 않고
    `exposure.include` + `SecurityConfig permitAll`로 처리.
- `SecurityConfig`: `/actuator/**` permitAll (로컬 실험 한정).
- `docker-compose.dev.yml`: `prometheus`(9090), `grafana`(3000). 레포 컨벤션에 맞춰 **bind mount**
  (`./data/dev/prometheus`, `./data/dev/grafana`) 사용 — top-level `volumes:` 블록이 없기 때문.
- `infra/prometheus/prometheus.yml`(backend:8080 5s 스크레이프), `infra/grafana/provisioning`(Prometheus DS).
- `LookieMetrics`: 실험 A/B/C용 커스텀 카운터·타이머 유틸. **이번엔 정의만**, 호출부 삽입은 실험 단계(이월).

## 위생 클린업 5건

| # | 내용 | 변경 파일 | 검증 | commit |
|---|---|---|---|---|
| 1 | 레거시 `ISSUE` 상태 잔존 참조 제거 | `TaskWorkflowFacade.determineNextActionAfterPick` | task 패키지 `grep '"ISSUE"'` → `ISSUE_PENDING`만 잔존 | `3e04188` |
| 2 | `completeItemManual` 도달 불가 모순 로직 정리 | `TaskItemService.completeItemManual` | 컴파일 + 로직상 IN_PROGRESS→DONE 정상 도달 | `248d7db` |
| 4 | AI 클라이언트 RestTemplate 타임아웃 (connect 3s / read 5s) | `AiClientConfig`(신규), `AiAnalysisClient`, `AiRebalanceClient`, `application.properties` | 컴파일 + `ai.client.*` 외부화 | `386967d` |
| 3 | `:progress` Redis 키 EXPIRE 24h | `WorkerMonitoringServiceRedisImpl.initializeZoneProgress` | (런타임) `redis-cli TTL lookie:control:zone:1:progress` 양수 | `d3f45e5` |
| 5 | 구역 상태 임계값 30분 외부화 | `WorkerMonitoringServiceRedisImpl.applyStatusFromEta`, `application.properties` | (런타임) `control.zone-status-threshold-minutes` 변경 시 `/api/control/zones` status 변동 | `d3f45e5` |

> 커밋은 파일 중첩(클린업 3·5가 `WorkerMonitoringServiceRedisImpl` 공유, 4·5가 `application.properties` 공유)으로
> 5건을 4커밋으로 묶었다. `application.properties`는 hunk 단위로 분리 스테이징. squash-merge 정책에선 무해.

### 클린업별 배경·판단

1. **레거시 ISSUE 참조** — `batch_task_items.status` ENUM은 `V2602071200`에서 `ISSUE`가 제거됨
   (`PENDING/IN_PROGRESS/ISSUE_PENDING/DONE`). `determineNextActionAfterPick`의 `"ISSUE".equals(...)`는
   더 이상 발생 불가능한 dead branch라 삭제.
2. **completeItemManual** — step1이 `IN_PROGRESS`를 조기 return skip 목록에 두어, `IN_PROGRESS`를 요구하는
   step3에 절대 도달하지 못했다(정상 완료 불가 모순). `IN_PROGRESS`를 skip에서 제외해 sibling
   `TaskWorkflowService.completeItem`과 동일한 FSM 의미로 교정. (이 메서드는 현재 `TaskController` 주경로가
   아닌 `TaskWorkflowFacade`(→ 미사용 `TaskLockExecutor`)에서만 참조되지만, 삭제 대신 명확화로 리스크 최소화.)
3. **:progress EXPIRE** — 설계상 "상위 배치 종료 24h 후 만료"였으나 실제 키에 TTL이 없어 무한 잔존.
   `initializeZoneProgress`의 `putAll` 직후 24h TTL 설정.
4. **AI 타임아웃** — 두 클라이언트가 `new RestTemplate()`(타임아웃 무제한)을 직접 생성 → AI 서버 지연 시
   호출 스레드 무한 대기 위험. `AiClientConfig`의 `aiRestTemplate` Bean으로 connect 3s/read 5s 외부화 적용.
5. **구역 임계값 외부화** — `applyStatusFromEta`의 하드코딩 `30` → `control.zone-status-threshold-minutes`.
   **판단**: `applyStatusFromEta`는 @Primary `WorkerMonitoringServiceRedisImpl`의 응답 경로에서 SQL 사전계산
   status를 **덮어쓰는 최종 판정값**이라 이 값 외부화만으로 검증 기준(값 변경 시 status 변동)이 충족된다.
   `ControlMapper.xml`의 SQL 사전계산 `30`은 동일 기본값이라 유지 — 이를 파라미터화하려면 `selectZoneOverviews()`
   **11개 호출부**(4개 서비스+스케줄러)에 인자를 스레딩해야 하는데, 덮어써지는 사전값이라 기능적 이득이 거의 없어
   저가치 churn으로 판단하여 제외. (플레이북의 XML 파라미터화 대비 의도적 축소 — 투명 기록.)

## Baseline 수집 (§0-5 안전장치)

- 스크립트: `docs/loadtest/scripts/collect_baseline.sh` (7개 GET × N회 순차, 응답시간·바디 → `baseline_raw/*.jsonl`)
- 집계: `docs/loadtest/scripts/summarize_baseline.py` → `docs/loadtest/BASELINE_METRICS.md`
- 대상: `/api/control/summary`, `/zones`, `/zones/{id}/workers`, `/zones/{id}/map`,
  `/workers/{id}/hover`, `/admins?zoneId=1`, `/api/users/me`
- **상태: 실측 완료** — 7개 엔드포인트 각 100회, 전부 100% 2xx. 결과 `BASELINE_METRICS.md`(p50/p95/p99·stddev·min/max·조건),
  원시 `baseline_raw/*.jsonl`.

## 런타임 검증 (완료)

`.env` 확보 후 최소 스택(mysql/redis/backend/prometheus/grafana, backend는 amd64 에뮬레이션) 기동하여 실측 검증 완료.
상세·근거는 **`RUNTIME_VERIFICATION.md`** 참조.

- Phase 0: `/actuator/prometheus`(application=lookie 태그), `up{job="lookie-backend"}=1`,
  PromQL `jvm_memory_used_bytes` 조회 성공, Grafana 데이터소스 프로비저닝 + 4701 대시보드 import.
- 클린업 3: `:progress` 4개 구역 모두 **TTL=86400** 확인.
- 클린업 5: 임계값 30→250 재기동 시 zone 2/3/4 STABLE→NORMAL 전이, 30 복구 확인.
- 헤드리스라 Grafana PNG는 미첨부(대시보드 import·URL 존재, UI에서 열람 가능).
