# 실험 A — 이중 리스너 진행률 정합성 (dedup)

> 브랜치 `experiment/exp-a-listener-dedup` (base: develop). 백엔드 amd64 에뮬레이션 기동.
> 원시 로그: `docs/loadtest/raw/exp_a_current_*.log`, `exp_a_treatment_*.log`. 드라이버: `scripts/exp_a_dual_listener.sh`.

## 1. 가설 · 배경
시연에서 "정상 집품 중 진행률이 어긋나는" 정합성 문제가 있었다(분석 §7 후보 1·3). 코드상 원인:
- `TaskItemCompletedEvent` **한 건**을 **두 리스너**가 각각 처리한다.
  - `TaskEventListener.onTaskItemCompleted` → `incrementZoneProgress` (멱등 가드 **없음**)
  - `ControlEventListener.handleTaskItemCompleted` (멱등 가드 있음, `@Async`)
- 두 리스너가 각각 `HINCRBY lookie:control:zone:{zoneId}:progress completed +1` → **한 이벤트당 +2**.
- 추가로, 되돌림 경로 일부(`reviveItem`를 호출하는 레거시 `IssueService`의 WebRTC-connected·confirm-NORMAL 분기)는
  차감 이벤트를 발행하지 않아 되돌림 시 감소가 누락됐다.

**불변식**: 구역 `:progress.completed` 증가량 == 처리한 이벤트 수 N.

## 2. 환경
- 로컬 Docker(단일 노드): mysql 8.0, redis 7-alpine, backend(**linux/amd64 에뮬레이션**), prometheus/grafana.
- 시드: `dummy-data-dashboard-test.sql` (batch_task_items 345). 트리거: 이슈 신고(`POST /api/issues`)가
  `TaskItemCompletedEvent`를 발행하는 경로 이용(완료·이슈 모두 동일 이벤트 → 이중 카운팅 메커니즘 동일).
- 계측: `LookieMetrics.lookie_zone_progress_listener_invocations_total{listener,result}` + Redis 해시 직접 조회.
- current는 zone 1, treatment는 zone 3(시드 아이템 소진으로 구역 이동 — 메커니즘은 구역 무관, 델타 비교라 영향 없음).

## 3. 측정 지표
- `task_event.incremented` / `task_event.skipped_dedup` (TaskEventListener 호출 성격)
- `control_event.incremented` / `control_event.skipped_idempotent` (ControlEventListener)
- `lookie:control:zone:{zoneId}:progress` 의 `completed` 증가량 (Δ)
- 처리 이벤트 수 N (드라이버가 통제)

## 4. 절차
1. 워커 로그인 → task 배정 → 해당 워커의 IN_PROGRESS task들의 PENDING 아이템 수집.
2. `:progress` 워밍(초기화 1회는 `completed`를 구역 DONE 수로 세팅하므로, 워밍 후 델타만 측정).
3. N개 아이템에 이슈 신고 = N개 이벤트 발행 → async 처리 대기(5s) → 메트릭·Redis 델타 측정.

## 5. 결과 (현행, current)
zone 1, N=15:

| 지표 | Δ |
|---|---|
| task_event.incremented | **15** |
| control_event.incremented | **15** |
| control_event.skipped_idempotent | 0 |
| **redis.completed** | **30 (= 2N)** |

→ 한 이벤트가 두 리스너에서 각각 +1 → `completed` **2배** 증가. 불변식 위반(2N).

## 6. 결과 (대조군, treatment)
대조군 코드: ① `TaskEventListener`는 진행률 증가 로직 제거(카운터는 `skipped_dedup`로만 관측), 진행률 갱신을
`ControlEventListener` **단독**으로 일원화. ② `reviveItem`이 `TaskItemRevertedEvent`를 **자체 발행**하도록 하고,
레거시 `IssueService`의 중복 발행 2곳(:482,:495) 제거 → 되돌림 감소가 **정확히 1회**.

zone 3, N=12:

| 지표 | Δ |
|---|---|
| task_event.incremented | **0** |
| task_event.skipped_dedup | 12 |
| control_event.incremented | **12** |
| **redis.completed** | **12 (= N)** |

→ 이벤트당 +1. 불변식 성립(N).

## 7. 해석
- **핵심 비율**: `completed 증가 / N` = 현행 **2.0** → 대조군 **1.0**. 이중 카운팅이 제거됐다.
- 두 리스너가 동일 이벤트를 처리하되 한쪽만 멱등 가드가 있어, 멱등 가드로도 막을 수 없는 구조적 중복이었음을
  메트릭(`task_event`/`control_event` 각각 N)으로 직접 확인.
- 되돌림 경로: 활성 서비스(`IssueServiceNew`)는 원래 정상 차감(변경 없음). 결함은 레거시 `IssueService`의 일부 분기였고,
  `reviveItem` 자체 발행으로 **호출부와 무관하게** 정합. (레거시 경로라 부하 측정 대신 빌드·코드경로 검증.)

## 8. 한계
- backend amd64 에뮬레이션 — 절대 지연은 참고용(본 실험은 카운트 정합성이라 지연 무관).
- N이 current(15)·treatment(12)로 다름(시드 아이템 소진) → 절대치 대신 `Δ/N` 비율로 비교.
- 이벤트 트리거로 완료(completeItem) 대신 이슈 신고를 사용(동일 이벤트·동일 리스너 경로). 완료 경로도 결과 동일.
- 되돌림 정합성은 레거시 경로라 부하 실측이 아닌 정적·빌드 검증.

## 9. 실서비스 함의
동일 도메인 이벤트를 여러 리스너가 처리할 때, **멱등·집계 책임을 한 곳으로 모으지 않으면** 한쪽의 멱등 가드가
전체 정합성을 보장하지 못한다. 집계(카운터) 갱신은 단일 리스너로 일원화하고, 역연산(증가/차감)은 같은 지점에서
대칭으로 처리해야 한다.

## 10. 면접 답변 요지
- **30초**: "진행률 카운터를 두 리스너가 같은 이벤트로 각각 올려 2배로 집계됐습니다. 한쪽만 멱등 처리라 가려졌고요.
  리스너를 하나로 합치고 되돌림 차감을 자체 발행으로 대칭화해서, 이벤트당 증가가 2→1로 정합됨을 메트릭으로 실측했습니다."
- **2분**: 위 + `:progress` 키 구조, `@TransactionalEventListener(AFTER_COMMIT)`·`@Async` 타이밍, 멱등 가드가
  구조적 중복을 못 막는 이유, 되돌림 누락(레거시 분기)까지.

## 부록: 채택
- 대조군을 실제 개선으로 채택 → `fix/dedup-listener-progress`(Phase 3). 실험 계측(`LookieMetrics` 호출부)은 fix에서 제거.
