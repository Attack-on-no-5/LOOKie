#!/usr/bin/env bash
# =============================================================================
# 실험 A — 이중 리스너 진행률 정합성 측정 드라이버
#
# 원리: TaskItemCompletedEvent 1건당 두 리스너(TaskEventListener/ControlEventListener)가
#       각각 :progress.completed 를 +1 → 현행은 이벤트 N건에 completed +2N (이중 카운팅).
# 트리거: 이슈 신고(POST /api/issues)가 TaskItemCompletedEvent를 발행하는 경로를 이용
#         (완료/이슈 모두 동일 이벤트를 발행하므로 이중 카운팅 메커니즘은 동일).
#
# 측정:
#   - 메트릭 lookie_zone_progress_listener_invocations_total{listener,result} 증가량
#   - Redis lookie:control:zone:1:progress 의 completed 증가량
#   - 처리한 이벤트 수 N (스크립트가 통제)
#
# 사용법:
#   BASE_URL=http://localhost:8080 WORKER_ID=010-3000-0001 WORKER_PW='Test1234!' \
#   ZONE_ID=1 N=15 LABEL=current bash docs/loadtest/scripts/exp_a_dual_listener.sh
# =============================================================================
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
WORKER_ID="${WORKER_ID:-010-3000-0001}"
WORKER_PW="${WORKER_PW:-Test1234!}"
ZONE_ID="${ZONE_ID:-1}"
N="${N:-15}"
LABEL="${LABEL:-run}"
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/raw"
mkdir -p "$OUT_DIR"
STAMP="$(date -u +%Y%m%d_%H%M%S)"
OUT="$OUT_DIR/exp_a_${LABEL}_${STAMP}.log"

metric_val() { # $1=listener $2=result → 현재 counter 값(없으면 0)
  curl -s "$BASE_URL/actuator/prometheus" \
    | grep "lookie_zone_progress_listener_invocations_total{" \
    | grep "listener=\"$1\"" | grep "result=\"$2\"" \
    | awk '{print $NF}' | head -1 | sed 's/\.0$//' || true
}
redis_completed() { docker exec redis redis-cli HGET "lookie:control:zone:${ZONE_ID}:progress" completed 2>/dev/null | tr -d '\r'; }

echo "[exp-a:$LABEL] worker 로그인" | tee "$OUT"
TOKEN=$(curl -s -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"phoneNumber\":\"$WORKER_ID\",\"password\":\"$WORKER_PW\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

# WORKER_UID: 워커 숫자 id (DB 조회용). 없으면 phone으로 조회.
WUID="${WORKER_UID:-$(docker exec mysql sh -lc "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" \"\$MYSQL_DATABASE\" -N -e \"SELECT user_id FROM users WHERE phone_number='$WORKER_ID';\"" 2>/dev/null | tr -d '\r')}"
echo "[exp-a:$LABEL] task 여러 개 배정 (worker uid=$WUID)" | tee -a "$OUT"
# N개 아이템 확보를 위해 task를 넉넉히 배정 (task당 아이템 수가 적음)
NEED_TASKS=$(( N / 2 + 2 ))
for k in $(seq 1 $NEED_TASKS); do
  curl -s -o /dev/null -X POST "$BASE_URL/api/tasks?zoneId=$ZONE_ID" -H "Authorization: Bearer $TOKEN" || true
done
# 이 워커가 보유한 IN_PROGRESS task들의 PENDING 아이템을 DB에서 직접 수집 (taskId:itemId)
PAIRS=$(docker exec mysql sh -lc "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" \"\$MYSQL_DATABASE\" -N -e \"
  SELECT CONCAT(bt.batch_task_id, ':', bti.batch_task_item_id)
  FROM batch_task_items bti JOIN batch_tasks bt ON bt.batch_task_id=bti.batch_task_id
  WHERE bt.worker_id=$WUID AND bt.status='IN_PROGRESS' AND bti.status='PENDING' LIMIT $N;\"" 2>/dev/null | tr -d '\r')
PAIR_ARR=($PAIRS)
AVAIL=${#PAIR_ARR[@]}
[ "$AVAIL" -lt "$N" ] && N=$AVAIL
echo "  사용 가능한 PENDING 아이템 $AVAIL개 → N=$N" | tee -a "$OUT"
if [ "$N" -eq 0 ]; then echo "  [SKIP] PENDING 아이템 없음"; exit 0; fi

# --- BEFORE 측정 ---
B_TASK=$(metric_val task_event incremented); B_TASK=${B_TASK:-0}
B_TDED=$(metric_val task_event skipped_dedup); B_TDED=${B_TDED:-0}
B_CTRL=$(metric_val control_event incremented); B_CTRL=${B_CTRL:-0}
B_SKIP=$(metric_val control_event skipped_idempotent); B_SKIP=${B_SKIP:-0}
B_RED=$(redis_completed); B_RED=${B_RED:-0}
echo "[BEFORE] task_event.inc=$B_TASK task_event.dedup=$B_TDED control_event.inc=$B_CTRL ctrl.skip=$B_SKIP redis.completed=$B_RED" | tee -a "$OUT"

# --- N개 이슈 신고 = N개 TaskItemCompletedEvent 발행 ---
ok=0
for i in $(seq 0 $((N-1))); do
  pair=${PAIR_ARR[$i]}; tid=${pair%%:*}; iid=${pair##*:}
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/issues" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"taskId\":$tid,\"taskItemId\":$iid,\"issueType\":\"OUT_OF_STOCK\"}")
  [ "$code" = "200" ] && ok=$((ok+1))
done
echo "  이슈 신고 성공 $ok/$N (=이벤트 $ok건)" | tee -a "$OUT"

echo "  async 리스너 처리 대기(5s)..." | tee -a "$OUT"
sleep 5

# --- AFTER 측정 ---
A_TASK=$(metric_val task_event incremented); A_TASK=${A_TASK:-0}
A_TDED=$(metric_val task_event skipped_dedup); A_TDED=${A_TDED:-0}
A_CTRL=$(metric_val control_event incremented); A_CTRL=${A_CTRL:-0}
A_SKIP=$(metric_val control_event skipped_idempotent); A_SKIP=${A_SKIP:-0}
A_RED=$(redis_completed); A_RED=${A_RED:-0}
echo "[AFTER ] task_event.inc=$A_TASK task_event.dedup=$A_TDED control_event.inc=$A_CTRL ctrl.skip=$A_SKIP redis.completed=$A_RED" | tee -a "$OUT"

echo "----- 결과 요약 ($LABEL) -----" | tee -a "$OUT"
echo "이벤트 수 N              = $ok" | tee -a "$OUT"
echo "task_event.incremented Δ = $((A_TASK - B_TASK))   (현행 N, 대조군 0)" | tee -a "$OUT"
echo "task_event.skipped_dedup Δ = $((A_TDED - B_TDED))   (현행 0, 대조군 N)" | tee -a "$OUT"
echo "control_event.incremented Δ = $((A_CTRL - B_CTRL))" | tee -a "$OUT"
echo "control_event.skip Δ     = $((A_SKIP - B_SKIP))" | tee -a "$OUT"
echo "redis.completed Δ        = $((A_RED - B_RED))   (현행 ≈ 2N, 대조군 ≈ N)" | tee -a "$OUT"
echo "저장: $OUT"
