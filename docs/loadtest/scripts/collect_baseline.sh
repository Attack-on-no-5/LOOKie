#!/usr/bin/env bash
# =============================================================================
# LOOKie baseline 원시 응답 수집 스크립트 (Phase 1 안전장치, §0-5)
# - 담당 도메인 GET 엔드포인트를 각 N회 순차 호출하여 응답시간·바디를 JSON Lines로 저장
# - 외부 API(LiveKit/AI/SMTP)를 호출하지 않는 조회 엔드포인트만 대상
#
# 사용법:
#   BASE_URL=http://localhost:8080 \
#   LOGIN_ID=<전화번호> LOGIN_PW=<비밀번호> \
#   ZONE_ID=1 WORKER_ID=1 N=100 \
#   bash docs/loadtest/scripts/collect_baseline.sh
#
# 결과: docs/loadtest/baseline_raw/{slug}.jsonl  (각 라인 = 1회 호출)
# 각 라인 필드: {"ts":ISO8601,"http_code":200,"time_total_s":0.0123,"bytes":1234,"body":<응답 JSON 또는 요약>}
# =============================================================================
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
N="${N:-100}"
ZONE_ID="${ZONE_ID:-1}"
WORKER_ID="${WORKER_ID:-1}"
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/baseline_raw"
mkdir -p "$OUT_DIR"

if [[ -z "${LOGIN_ID:-}" || -z "${LOGIN_PW:-}" ]]; then
  echo "[ERROR] LOGIN_ID / LOGIN_PW 환경변수가 필요합니다 (시드 계정 자격증명)." >&2
  exit 1
fi

echo "[baseline] 로그인 (POST /api/auth/login)"
LOGIN_RESP="$(curl -sS -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"phoneNumber\":\"$LOGIN_ID\",\"password\":\"$LOGIN_PW\"}")"

# accessToken 추출 (jq 있으면 jq, 없으면 grep)
if command -v jq >/dev/null 2>&1; then
  TOKEN="$(printf '%s' "$LOGIN_RESP" | jq -r '.data.accessToken // .accessToken // empty')"
else
  TOKEN="$(printf '%s' "$LOGIN_RESP" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/.*:"//;s/"$//')"
fi
if [[ -z "$TOKEN" ]]; then
  echo "[ERROR] accessToken 추출 실패. 로그인 응답: $LOGIN_RESP" >&2
  exit 1
fi
echo "[baseline] 토큰 확보 완료"

# slug|path 목록 (관리자 권한 필요 엔드포인트 포함 → 시드 계정이 ADMIN이어야 함)
ENDPOINTS=(
  "control_summary|/api/control/summary"
  "control_zones|/api/control/zones"
  "control_zone_workers|/api/control/zones/${ZONE_ID}/workers"
  "control_zone_map|/api/control/zones/${ZONE_ID}/map"
  "control_worker_hover|/api/control/workers/${WORKER_ID}/hover"
  "control_admins|/api/control/admins?zoneId=${ZONE_ID}"
  "users_me|/api/users/me"
)

for entry in "${ENDPOINTS[@]}"; do
  slug="${entry%%|*}"
  path="${entry##*|}"
  out="$OUT_DIR/${slug}.jsonl"
  : > "$out"
  echo "[baseline] ${slug} (${path}) x ${N}"
  for i in $(seq 1 "$N"); do
    ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    # -w로 http_code/time_total/size, 바디는 별도 캡처
    resp="$(curl -sS -o /tmp/lookie_baseline_body -w '%{http_code} %{time_total} %{size_download}' \
      -H "Authorization: Bearer $TOKEN" "$BASE_URL$path" || echo '000 0 0')"
    code="$(echo "$resp" | awk '{print $1}')"
    tt="$(echo "$resp" | awk '{print $2}')"
    sz="$(echo "$resp" | awk '{print $3}')"
    body="$(tr -d '\n' < /tmp/lookie_baseline_body | head -c 4000)"
    if command -v jq >/dev/null 2>&1; then
      jq -cn --arg ts "$ts" --argjson code "$code" --argjson tt "$tt" --argjson sz "$sz" --arg body "$body" \
        '{ts:$ts,http_code:$code,time_total_s:$tt,bytes:$sz,body:$body}' >> "$out"
    else
      printf '{"ts":"%s","http_code":%s,"time_total_s":%s,"bytes":%s,"body":%s}\n' \
        "$ts" "$code" "$tt" "$sz" "$(printf '%s' "$body" | sed 's/\\/\\\\/g;s/"/\\"/g' | awk '{print "\""$0"\""}')" >> "$out"
    fi
  done
done

echo "[baseline] 완료. 결과: $OUT_DIR/*.jsonl"
echo "[baseline] 요약(p50/p95/p99)은 summarize_baseline.py 또는 수동 집계로 BASELINE_METRICS.md에 기록."
