#!/usr/bin/env bash
# 실험 C — 관리자 배정 check-then-act 경합 드라이버
#
# 목적: 특정 구역에 "가용 관리자 1명"만 남긴 상태에서 다수 워커가 거의 동시에
#       화상 발신(POST /api/webrtc)을 보내면, 선택(read)과 예약(setUserBusy write)
#       사이의 비원자 구간 때문에 같은 관리자가 중복 배정되는지 측정한다.
#
# 사용법:
#   docs/loadtest/scripts/exp_c_manager_race.sh [동시요청수 N] [라운드수 R]
#   기본값: N=10, R=5
#
# 전제(호출 전 셋업):
#   - zone1 관리자 중 56만 가용, 49/48은 user:status=AWAY 로 막아둔다.
#   - zone1 워커 ID 풀에서 callerId를 순환 사용한다(모두 zone1 → 관리자 56만 후보).
#   - /api/webrtc/** 는 permitAll 이라 토큰 불필요.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
N="${1:-10}"          # 라운드당 동시 요청 수
ROUNDS="${2:-5}"      # 라운드 반복 수
AVAIL_MANAGER=56      # 유일하게 가용으로 둘 관리자
AWAY_MANAGERS=(49 48) # AWAY 로 막을 나머지 zone1 관리자
# zone1 워커 ID 풀 (callerId 로 순환)
WORKERS=(1 2 3 4 5 6 7 8 9 10)

RAW_DIR="$(cd "$(dirname "$0")/.." && pwd)/raw"
mkdir -p "$RAW_DIR"
STAMP="$(date +%Y%m%d_%H%M%S)"
LOG="$RAW_DIR/exp_c_${STAMP}.log"

redis() { docker exec redis redis-cli "$@"; }
mysql_q() { docker exec mysql sh -lc "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" \"\$MYSQL_DATABASE\" -N -e \"$1\""; }
metric() { curl -s "$BASE_URL/actuator/prometheus" | grep -E "^$1" || true; }

log() { echo "$@" | tee -a "$LOG"; }

reset_state() {
  # 가용 관리자 BUSY 해제, 나머지 zone1 관리자 AWAY 고정
  redis del "user:status:${AVAIL_MANAGER}" >/dev/null
  for m in "${AWAY_MANAGERS[@]}"; do redis set "user:status:${m}" AWAY EX 3600 >/dev/null; done
}

log "=== 실험 C: 관리자 배정 경합 (N=$N, ROUNDS=$ROUNDS) ==="
log "시작: $(date '+%F %T')  BASE_URL=$BASE_URL  가용관리자=$AVAIL_MANAGER"
log ""

# 측정 시작 지점 기록
BASELINE_MAX_ID=$(mysql_q "SELECT COALESCE(MAX(id),0) FROM call_history;")
log "[baseline] call_history MAX(id)=$BASELINE_MAX_ID"
log "[baseline metrics]"
metric "lookie_manager_double_assign_total" | tee -a "$LOG"
metric "lookie_manager_select_retry_total"  | tee -a "$LOG"
metric "lookie_manager_atomic_reserve_total" | tee -a "$LOG"
log ""

for r in $(seq 1 "$ROUNDS"); do
  reset_state
  log "--- Round $r/$ROUNDS ---"
  RESP_DIR="$(mktemp -d)"
  # 동시 발신: 백그라운드 curl 로 최대한 근접 발사
  for i in $(seq 0 $((N-1))); do
    caller="${WORKERS[$(( i % ${#WORKERS[@]} ))]}"
    curl -s -o "$RESP_DIR/$i.json" -w '%{http_code}' \
      -X POST "$BASE_URL/api/webrtc" \
      -H 'Content-Type: application/json' \
      -d "{\"callerId\":$caller}" > "$RESP_DIR/$i.code" &
  done
  wait

  # 라운드 결과 집계: 성공(WAITING 생성) vs 관리자 BUSY 실패
  ok=0; busy=0; other=0
  for i in $(seq 0 $((N-1))); do
    code=$(cat "$RESP_DIR/$i.code")
    if grep -q '"success":true' "$RESP_DIR/$i.json" 2>/dev/null; then
      ok=$((ok+1))
    elif grep -q 'RTC_002\|MANAGER_BUSY\|가용한 관리자\|부재중\|"success":false' "$RESP_DIR/$i.json" 2>/dev/null; then
      busy=$((busy+1))   # 관리자 예약 실패(원자 예약에서 밀림) → 정상 거절
    else
      other=$((other+1))
    fi
  done
  log "  응답: 성공(배정)=$ok  관리자BUSY=$busy  기타=$other  (code 샘플: $(cat "$RESP_DIR"/0.code))"
  rm -rf "$RESP_DIR"
done

log ""
log "=== 측정 종료 집계 ==="
# 이번 실험에서 관리자 56에게 배정된 WAITING 통화 건수(= 중복 배정의 지상 증거)
log "[call_history] baseline 이후 callee=$AVAIL_MANAGER 배정 건수(라운드별 2건 이상이면 중복):"
mysql_q "SELECT caller_id, callee_id, status, created_at FROM call_history WHERE id > $BASELINE_MAX_ID AND callee_id=$AVAIL_MANAGER ORDER BY id;" | tee -a "$LOG"
log ""
log "[call_history] 근접시각 중복배정 그룹(같은 callee, 동일 초 내 2건+):"
mysql_q "SELECT callee_id, created_at, COUNT(*) c FROM call_history WHERE id > $BASELINE_MAX_ID GROUP BY callee_id, created_at HAVING c >= 2 ORDER BY created_at;" | tee -a "$LOG"
log ""
log "[end metrics]"
metric "lookie_manager_double_assign_total" | tee -a "$LOG"
metric "lookie_manager_select_retry_total"  | tee -a "$LOG"
metric "lookie_manager_atomic_reserve_total" | tee -a "$LOG"
log ""
log "완료: $(date '+%F %T')  로그=$LOG"
