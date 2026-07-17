# 실험 B — Hover 폴링 DB 부하 & Redis Cache-Aside 대조 리포트

> 대상: `GET /api/control/workers/{id}/hover` → `WorkerMonitoringServiceRedisImpl.getWorkerHoverInfo` → `ControlMapper.selectWorkerHoverInfo`
> 결론: **hover 조회가 매 호출 DB 직결(상관 서브쿼리 3개+다중 조인). 30초 Cache-Aside로 DB 호출 약 98% 감소, 캐시 히트 경로는 약 100배 빠름.**
> 성격: **참고용 성능 실험(fix 채택 아님).** 캐시 도입은 hover 신선도(최대 30s stale) vs DB 부하의 제품 결정 사항.

## 1. 문제 정의

관제 맵에서 작업자에 마우스오버할 때마다 호출되는 hover 조회는 이름/구역/오늘 처리량/최근 이슈를 반환한다.
설계 의도는 "Redis 우선"이었으나 실제 구현(`getWorkerHoverInfo`)은 **매 호출마다 DB 직결**이며 rate-limit·캐시가 없다.

쿼리(`selectWorkerHoverInfo`)는 다음을 한 번에 계산한다.
- 상관 서브쿼리 3개: 오늘 처리량(`todayWorkCount`), 최근 이슈 타입, 최근 이슈 ID
- 조인: `users` × `work_logs`(활성) × `batch_tasks`(진행중) × `zone_locations`

hover는 마우스 움직임에 따라 **고빈도로 반복 호출**되므로, 매번 이 쿼리가 DB 커넥션을 점유하면 관제 부하가 커진다.

## 2. 실험 설계

| 항목 | 값 |
|---|---|
| 부하 도구 | k6 `constant-arrival-rate` (`docs/loadtest/scripts/exp_b_hover_load.js`) |
| 시나리오 | 25 rps / 100 rps × 30초, 단일 활성 작업자 hover 반복 |
| 인증 | `/api/control/**` 인증 필요 → setup에서 ADMIN 로그인 토큰 재사용 |
| 대조군 전환 | `control.hover-cache-enabled` (false=현행 DB직결, true=Cache-Aside) |
| 캐시 | Redis key `lookie:control:worker:{id}:hover`, TTL 30초, 가공 완료 DTO 캐싱 |
| 무효화 | 작업 완료/되돌림 이벤트(`ControlEventListener`)에서 해당 작업자 캐시 삭제 |
| 지표 | hover DB 호출 수(`lookie_hover_db_calls_total`), 캐시 히트(`lookie_hover_cache_hits_total`), p95/p99, 처리량 |

> 검증 환경은 로컬 Docker(backend **linux/amd64 에뮬레이션**) → **절대 지연은 과장**되며 상대 비교용.
> 100 rps에서는 VU 풀·DB 커넥션 풀 포화로 두 경우 모두 대기가 지배적 → **DB 호출 수/캐시 히트율이 가장 깨끗한 지표**, 지연은 방향성으로 해석.

## 3. 결과

### 25 rps (30초)
| 지표 | 현행(DB 직결) | 대조군(Cache-Aside) |
|---|---|---|
| 달성 처리량 | 16.9 rps | **19.6 rps** |
| hover DB 호출 | **580** | **10** (약 98%↓) |
| 캐시 히트율 | — | **592/602 = 98.3%** |
| 응답 avg | 6.57s | **4.95s** |
| 응답 p95 / p99 | 13.0s / 14.4s | **10.5s / 11.3s** |
| 최소 지연(캐시 히트 경로) | 352ms | **3.47ms** (약 100×) |
| 에러율 | 0% | 0% |

### 100 rps (30초, 포화 구간)
| 지표 | 현행 | 대조군 |
|---|---|---|
| 달성 처리량 | 9.45 rps | **15.3 rps** (+62%) |
| hover DB 호출 | 386 | **12** (약 97%↓) |
| 캐시 히트율 | — | 551/563 = **97.9%** |
| 응답 p95 / p99 | 33.9s / 34.8s | **26.5s / 29.9s** |
| 완료 / 드롭 | 386 / 2614 | 563 / 2438 |

**요지**: hover DB 호출이 **580→10 / 386→12(약 98% 감소)**, 캐시 히트율 **~98%**. 캐시 히트 경로 최소 지연 **3.47ms**로 DB 경로(352ms) 대비 약 100배. 포화 구간에서도 처리량·p95/p99 전반 개선.

원시 로그: `docs/loadtest/raw/exp_b_{treatment,control}_*.log`.

## 4. 대조군 구현 요지

```java
// control.hover-cache-enabled=true 일 때만 활성
String key = "lookie:control:worker:" + workerId + ":hover";
String cached = redis.get(key);
if (cached != null) { cacheHit++; return deserialize(cached); }   // 히트: DB 미접근
WorkerHoverDto dto = loadWorkerHoverFromDb(workerId);             // 미스: DB 1회
redis.set(key, serialize(dto), 30, SECONDS);                     // 30s TTL 캐싱
return dto;
```
- 가공(이름 포맷·Zone명 매핑) 완료된 DTO를 캐싱 → 히트 시 추가 연산 없음.
- 무효화: `ControlEventListener`가 완료/되돌림 이벤트에서 `batchTaskId→workerId`를 해석해 해당 캐시 삭제(공유 이벤트 클래스 미변경, 비동기 AFTER_COMMIT).

## 5. 인지된 한계 / 트레이드오프

- **신선도**: 캐시 히트는 최대 30초 stale. 이벤트 무효화가 활성 작업자의 상태 변화를 반영하나, 그 사이 조회는 이전 스냅샷.
- **에뮬레이션**: amd64 에뮬레이션으로 절대 지연이 과장됨. 상대 비교(호출 수·히트율·방향성)만 유효.
- **글로벌 카운터 노이즈**: MySQL `Questions` 전역 카운터는 배경 트래픽이 섞여 hover 부하 격리에 부적합 → 엔드포인트 전용 카운터(`lookie_hover_db_calls_total`)로 측정.
- **채택 아님**: 신선도 요구가 높으면 캐시가 부적절할 수 있어 fix로 채택하지 않음. 참고 브랜치·리포트로만 남김.
- **부수 관찰(캐시와 무관)**: 한 작업자에게 IN_PROGRESS `batch_tasks`가 여러 건이면 `selectWorkerHoverInfo`가 조인 곱으로 2행 이상을 반환해 `selectOne`이 `TooManyResultsException`을 던진다(시드 데이터의 worker 1 사례). hover 쿼리의 단일행 보장(예: `LIMIT 1`/서브쿼리화) 검토 필요 — 별도 이슈.
