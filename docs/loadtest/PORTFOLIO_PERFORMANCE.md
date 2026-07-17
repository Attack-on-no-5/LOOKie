# LOOKie 성능·정합성 실험 통합 리포트

> 실시간 물류 관제 백엔드(Spring Boot 3.2.2 / MyBatis / MySQL 8 / Redis 7 / STOMP / LiveKit)의
> **성능·동시성·정합성** 실험 3건을 대조군 방식으로 검증한 결과 모음. 각 실험은 결함을 대조군에서 입증 후
> 실제 fix로 채택하거나(참고용은 미채택) 리포트로 남겼다.
>
> 개별 리포트: [실험 A](EXPERIMENT_A_LISTENER_DEDUP.md) · [실험 B](EXPERIMENT_B_HOVER_CACHE.md) · [실험 C](EXPERIMENT_C_MANAGER_RACE.md)
> baseline: [BASELINE_METRICS.md](BASELINE_METRICS.md)

## 0. 측정 환경 (공통)

- 하드웨어: MacBook M2(arm64), 16GB. 백엔드는 **Docker linux/amd64 에뮬레이션**(alpine JRE arm64 미매치) → **절대 지연은 과장**되며 모든 수치는 **상대 비교**용.
- 스택: MySQL 8.0 / Redis 7-alpine / backend / Prometheus / Grafana (compose, **단일 노드**).
- 관측성(Phase 0): Actuator + Micrometer → `/actuator/prometheus`, 커스텀 메트릭 `LookieMetrics`.
- 시드: `dummy-data-dashboard-test.sql` (users 57, zones 4, batches 2, batch_task_items 345).

### Baseline (담당 GET 7종 × 100회 순차, 100% 2xx)
| 엔드포인트 | p50 | p95 | p99 (ms) |
|---|---|---|---|
| users_me | 6.8 | 12.3 | 29.9 |
| control_worker_hover | 7.8 | 15.8 | 53.6 |
| control_admins | 8.9 | 23.2 | 58.9 |
| control_zones | 13.0 | 25.2 | 208.1 |
| control_zone_workers | 20.5 | 40.2 | 72.6 |
| control_zone_map | 26.0 | 114.6 | 213.3 |
| control_summary | 30.5 | 67.7 | 248.8 |

## 1. 실험 요약 (3건)

| # | 실험 | 유형 | 핵심 결과 | 채택 |
|---|---|---|---|---|
| **A** | 이중 리스너 진행률 정합성 | 정합성(버그) | 진행률 증가 **2N → N** (이중 카운팅 제거) | ✅ `fix/dedup-listener-progress` (develop 머지, PR#2) |
| **C** | 관리자 배정 check-then-act 경합 | 동시성(버그) | 동시 10건 중복배정 **45건 → 0건** | ✅ `fix/manager-atomic-assign` (develop 머지, PR#3) |
| **B** | Hover 폴링 DB 부하 | 성능(참고) | hover DB 호출 **약 98%↓**, 캐시 히트율 ~98% | ⬜ 참고용(신선도 트레이드오프, 미채택) |

---

### 실험 A — 이중 리스너 진행률 정합성
- **문제**: `TaskItemCompletedEvent` 한 건을 `TaskEventListener`(멱등 가드 없음)와 `ControlEventListener`(멱등 O)가 **둘 다** 처리 → 구역 `:progress.completed`가 이벤트당 **+2**. 멱등 가드로도 못 막는 구조적 중복.
- **측정**: 이슈 신고로 N건 이벤트 발행 → 메트릭 + Redis 해시 델타.
  - 현행: N=15 → `completed` Δ **30 (=2N)**. 대조군: N=12 → Δ **12 (=N)**. 비율 **2.0 → 1.0**.
- **fix**: 진행률 갱신을 `ControlEventListener` 단독으로 일원화 + dead `incrementZoneProgress` 제거 + 되돌림(`reviveItem`)이 `TaskItemRevertedEvent`를 자체 발행해 **정확히 1회** 차감.

### 실험 C — 관리자 자동 배정 경합
- **문제**: 관리자 선택(read: `user:status==null` 필터)과 `setUserBusy`(write: 비원자 `set`) 사이 TOCTOU. 동시 발신 시 같은 관리자 중복 배정 → 1:1 제약 붕괴.
- **측정**: 가용 관리자 1명만 둔 구역에 동시 10건 발신 × 5라운드.
  - 현행: 라운드당 **10/10 배정**(5R 중복 **45건**). 대조군(`setIfAbsent` SETNX+TTL): **1/10**, 중복 **0건**. `atomic_reserve` acquired 5 / failed 45.
- **fix**: `setUserBusy` 제거 → `reserveManager`(SETNX) 원자 예약, 선택 단계에서 예약 성공한 첫 관리자 확정.
- **부가**: `TaskLockExecutor`(Redisson RLock)는 호출부 0 dead code 확인(Task는 DB 비관락 `findByIdForUpdate`로 대체) → 도메인별 락 전략 분리(Task=DB 비관락, 관리자 배정=Redis SETNX).

### 실험 B — Hover 폴링 DB 부하 (참고용)
- **문제**: `getWorkerHoverInfo`가 매 호출 DB 직결(상관 서브쿼리 3개 + 4테이블 조인). rate-limit/캐시 없음. hover는 마우스오버마다 고빈도 호출.
- **측정(k6)**: @25rps — hover DB 호출 **580 → 10(98%↓)**, 캐시 히트율 **98.3%**, p95 13.0s→10.5s, 캐시 히트 경로 min **3.47ms** vs DB 352ms(~100배). @100rps(포화) — 386 → 12, 처리량 9.45 → 15.3 rps.
- **대조군**: Redis Cache-Aside 30s TTL(`control.hover-cache-enabled` 토글, 기본 false) + `ControlEventListener` 이벤트 무효화. **미채택**(신선도 최대 30s stale은 제품 결정).

## 2. 설계 의도와 실제 구현의 상이 (실험으로 드러난 것)

| 항목 | 설계 의도 | 실제 구현 | 실험 |
|---|---|---|---|
| 진행률 집계 | 단일 리스너가 멱등 처리 | 두 리스너가 동일 이벤트 이중 처리 | A |
| 관리자 예약 | 원자적 1:1 선점 | check-then-act 비원자(`set`) | C |
| Hover 조회 | "Redis 우선" | 매 호출 DB 직결 | B |
| 관리자 재시도 | 견고한 재시도 | `for(3)+Thread.sleep(100)` (Spring Retry/Resilience4j 아님) | C |
| Task 분산락 | Redisson RLock | dead code, DB 비관락으로 대체 | C |

## 3. 인지된 한계 (전체)

- **환경**: 단일 노드 + **amd64 에뮬레이션** → 절대 지연 과장, 상대 비교만 유효. 다중 노드/HA는 범위 밖(SETNX는 유효하나 장애 시 red-lock 등 별도 고려).
- **부하 격리**: MySQL 전역 `Questions` 카운터는 배경 트래픽이 섞여 부적합 → 엔드포인트 전용 커스텀 카운터로 측정(실험 B 교훈).
- **포화 구간**: 100rps는 VU 풀·HikariCP 커넥션 풀 포화라 지연이 대기에 지배 → DB 호출수·히트율·중복건수 등 **카운팅 지표**가 깨끗한 근거.
- **실험 B 신선도**: 캐시 히트는 최대 30s stale(이벤트 무효화로 창 축소). 채택 시 신선도 SLA 합의 필요.
- **부수 발견(별도 이슈)**: 한 작업자에 IN_PROGRESS `batch_tasks`가 여럿이면 `selectWorkerHoverInfo`가 조인 곱으로 다행 반환 → `selectOne` `TooManyResultsException`(500). hover 쿼리 단일행 보장(`LIMIT 1`) 검토.
- **분석 문서(§0-4/9) 기반 기타 인지 한계**: 관제 status/webrtcStatus 일부 하드코딩, `processing_speed` 더미값, soft delete 시 전화번호 변조 미구현, WebSocket 커밋 순서 의존, CircuitBreaker 미도입 등 — 실험 범위 밖으로 문서화만.

## 4. 브랜치 / 산출물 맵

| 실험 | fix 브랜치(채택) | 실험 브랜치(계측, 로컬) | 리포트 | 드라이버 |
|---|---|---|---|---|
| A | `fix/dedup-listener-progress`(머지) | `experiment/exp-a-listener-dedup` | EXPERIMENT_A_LISTENER_DEDUP.md | scripts/exp_a_dual_listener.sh |
| C | `fix/manager-atomic-assign`(머지) | `experiment/exp-c-manager-atomic` | EXPERIMENT_C_MANAGER_RACE.md | scripts/exp_c_manager_race.sh |
| B | — (미채택) | `experiment/exp-b-hover-cache` | EXPERIMENT_B_HOVER_CACHE.md | scripts/exp_b_hover_load.js |

> 원시 로그(`docs/loadtest/raw/*`)는 gitignore(로컬). 각 리포트에 수치 인라인.
