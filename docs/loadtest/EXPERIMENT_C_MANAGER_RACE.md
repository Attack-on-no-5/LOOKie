# 실험 C — 관리자 자동 배정 경합 (check-then-act) 검증 리포트

> 대상 코드: `LiveKitService.makeCall` / `selectAvailableManager` / `setUserBusy`
> 결론: **현행은 동시 발신 시 같은 관리자가 중복 배정됨(1:1 제약 붕괴). SETNX 원자 예약으로 완전 해소.**
> 채택 fix 브랜치: `fix/manager-atomic-assign`

## 1. 문제 정의

작업자가 화상 발신(`POST /api/webrtc`)을 하면 서버가 같은 구역의 가용 관리자를 1명 자동 배정한다.
배정 흐름은 다음과 같다.

```
selectAvailableManager()               // (read)  Redis user:status:{id} == null 인 관리자만 필터 → shuffle → 첫 번째
  ↓
makeCall(): validateUserAvailable()    // (read)  다시 상태 확인
  ↓
callHistoryMapper.save()               // 통화 기록 생성(WAITING)
  ↓
setUserBusy()                          // (write) opsForValue().set(BUSY, TTL 10m)  ← SETNX 아님
```

**문제**: 관리자를 "선택하는 read"와 "BUSY로 잡는 write" 사이가 원자적이지 않다.
두 작업자가 거의 동시에 발신하면, 둘 다 관리자를 아직 `null(가용)`로 읽은 뒤 각자 `set(BUSY)`로 덮어써서
**같은 관리자에게 중복 배정**된다. 재시도 로직(`selectAvailableManagerWithRetry`)은 순수 `for(3회) + Thread.sleep(100)`이라
동시성 자체를 막지 못한다(락·원자예약 없음).

## 2. 실험 설계

| 항목 | 값 |
|---|---|
| 셋업 | zone1 관리자 3명 중 **56만 가용**, 49·48은 `user:status=AWAY`로 차단 |
| 부하 | 라운드당 **동시 10건** `POST /api/webrtc`(zone1 워커 callerId 순환), **5라운드** |
| 기대 정상값 | 라운드당 **성공 배정 1건 + 거절 9건** (가용 관리자 1명 = 1:1) |
| 드라이버 | `docs/loadtest/scripts/exp_c_manager_race.sh` |
| 지상 증거 | `call_history`에서 같은 `callee_id`로 근접시각 배정된 WAITING 건수 |
| 보조 지표 | `lookie_manager_double_assign_total`, `_select_retry_total`, `_atomic_reserve_total{result}` |

> 검증 환경은 로컬 Docker(backend는 linux/amd64 에뮬레이션). 절대 지연이 아니라 **배정 정합성(건수)**를 본다.
> 에뮬레이션이 요청을 직렬화해 경합이 안 터질 리스크가 있었으나, 현행에서 10건이 전부 성공하며 경합이 확실히 재현됐다.

## 3. 결과

| 지표 | 현행 (`set`) | 대조군 (`setIfAbsent`) |
|---|---|---|
| 라운드당 성공 배정 | **10 / 10** | **1 / 10** |
| 라운드당 거절(409) | 0 | 9 |
| 5라운드 관리자 56 WAITING 통화 | **50건** | **5건** |
| 근접시각 중복배정 그룹(동일 초 2건+) | **5개 그룹(각 10건)** | **0** |
| `manager_atomic_reserve_total{acquired}` | — | **5** (라운드당 정확히 1) |
| `manager_atomic_reserve_total{failed}` | — | **45** (밀린 요청, 정상 거절) |
| `manager_select_retry_total` | — | 90 (패자 재시도) |

- **현행**: 동시 10건이 **전부** 관리자 56에 배정. 1:1 제약이 완전히 깨진다(라운드당 9건 중복 배정, 5라운드 45건).
  in-code 중복 탐지 카운터는 2만 포착했는데, 탐지 read가 대부분 `set(BUSY)` 이전에 실행돼 **DB 기록이 지상 증거**다.
- **대조군**: `setIfAbsent`(SETNX+TTL)로 동시 10건 중 **정확히 1건만 예약 성공**, 나머지 9건은 `RTC_002`로 정상 거절.
  중복 배정 0건.

원시 로그: `docs/loadtest/raw/exp_c_*.log` (현행/대조군 각 1개).

## 4. 채택 대조군 = fix

`setUserBusy`(비원자 `set`)를 제거하고, **선택 단계에서 `setIfAbsent`로 원자 예약**한다.

```java
// 가용 후보를 shuffle 순서로 돌며, SETNX 예약 성공한 첫 관리자를 확정
Collections.shuffle(availableManagers);
for (UserVO manager : availableManagers) {
    if (reserveManager(manager.getUserId())) return manager.getUserId();
}
throw new ApiException(ErrorCode.WEBRTC_MANAGER_BUSY, "...");

private boolean reserveManager(Long userId) {
    Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(USER_STATUS_KEY + userId, "BUSY", 10, TimeUnit.MINUTES);
    return Boolean.TRUE.equals(acquired);
}
```

- 명시적 `calleeId` 지정 경로도 동일하게 `reserveManager`로 예약(실패 시 `validateUserAvailable`로 사유 구체화).
- 재시도 래퍼의 사후 `validateUserAvailable` 재검증은 제거(예약이 이미 자기 상태를 BUSY로 만들어 오탐 유발).
- 해제(`clearUserStatus`)·TTL 정책은 기존과 동일.

## 5. 락 선택지 비교 (설계 논의)

| 방식 | 원자성 | 이 도메인 적용 | 비고 |
|---|---|---|---|
| 무락 + 재시도(현행) | ✗ | check-then-act 경합 | `for(3) + sleep(100)`, 동시성 미해결 |
| **Redis SETNX(`setIfAbsent`)** | ✓ | **채택** | 단일 원자 연산, TTL로 자동 해제, 노드 1대 환경에 충분 |
| Redisson `RLock`(분산락) | ✓ | 과함 | 아래 `TaskLockExecutor`가 이미 이 방식이었으나 dead code화 |

**참고 — `TaskLockExecutor`(Redisson RLock) dead code**: Task 도메인은 원래 Redis 분산락으로 할당/완료를 처리했으나
(`319c4d4` 도입), 이후 리팩터로 **DB 비관락(`findByIdForUpdate`, `SELECT … FOR UPDATE`)** 으로 대체되며
`TaskLockExecutor.startTask/completeTask`는 **호출부 0의 고아 코드**로 남았다(`882f789`, `b3656ff`에서 레거시 제거).
즉 이 프로젝트는 이미 도메인별로 락 전략이 갈려 있다 — **Task는 DB 비관락, 관리자 배정은(본 fix) Redis SETNX**.

## 6. 인지된 한계

- TTL 10분 예약이므로, 예약 후 통화가 정상 종료/취소되지 않으면 최대 10분간 관리자가 묶인다(기존 동작과 동일).
- 예약 성공 후 `callHistoryMapper.save` 등 후속 로직이 실패하면 예약이 잔존(보상 해제 없음) — 기존 `setUserBusy`와 동일한 트레이드오프. TTL로 자연 회수.
- 단일 Redis 노드 기준. 다중 노드/HA에서는 SETNX 자체는 유효하나 장애 시 red-lock 등 별도 고려 필요(현 범위 밖).
