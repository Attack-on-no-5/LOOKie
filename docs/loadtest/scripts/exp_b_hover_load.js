// 실험 B — Hover 폴링 부하 테스트 (k6)
//
// 관리자 관제 맵에서 작업자 마우스오버 시 호출되는 GET /api/control/workers/{id}/hover 를
// constant-arrival-rate 로 폭격해 현행(DB 직결) vs 대조군(Redis Cache-Aside) 응답 지연·에러율을 비교한다.
//
// 사용법:
//   k6 run docs/loadtest/scripts/exp_b_hover_load.js
//   RATE=100 DURATION=30s WORKER_ID=2 k6 run docs/loadtest/scripts/exp_b_hover_load.js
//
// 전제:
//   - /api/control/** 는 인증 필요 → setup 에서 ADMIN 로그인 후 토큰 재사용.
//   - WORKER_ID 는 hover 쿼리가 정확히 1행을 반환하는 활성 작업자여야 함(IN_PROGRESS task 1개).

import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const WORKER_ID = __ENV.WORKER_ID || '2';
const RATE = parseInt(__ENV.RATE || '100', 10);
const DURATION = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    hover_poll: {
      executor: 'constant-arrival-rate',
      rate: RATE,          // 초당 요청 수
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 300,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'], // 에러율 1% 미만
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const res = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ phoneNumber: '010-9000-0001', password: 'Test1234!' }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  const token = res.json('data.accessToken');
  if (!token) {
    throw new Error(`로그인 실패: status=${res.status} body=${res.body}`);
  }
  return { token };
}

export default function (data) {
  const res = http.get(`${BASE}/api/control/workers/${WORKER_ID}/hover`, {
    headers: { Authorization: `Bearer ${data.token}` },
  });
  check(res, {
    'status 200': (r) => r.status === 200,
    'success true': (r) => r.json('success') === true,
  });
}
