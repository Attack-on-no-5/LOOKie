# LOOKie Baseline Metrics

- 측정 시각(집계): 2026-07-09T11:02:18.247940+00:00
- 원시 데이터: `docs/loadtest/baseline_raw/*.jsonl` (7개 엔드포인트)

| 엔드포인트 | N | 2xx% | p50(ms) | p95(ms) | p99(ms) | mean(ms) | stddev(ms) | min(ms) | max(ms) |
|---|---|---|---|---|---|---|---|---|---|
| control_admins | 100 | 100.0 | 8.9 | 23.2 | 58.9 | 12.1 | 13.1 | 6.3 | 123.8 |
| control_summary | 100 | 100.0 | 30.5 | 67.7 | 248.8 | 38.6 | 37.5 | 13.1 | 284.5 |
| control_worker_hover | 100 | 100.0 | 7.8 | 15.8 | 53.6 | 11.9 | 28.1 | 5.6 | 286.7 |
| control_zone_map | 100 | 100.0 | 26.0 | 114.6 | 213.3 | 38.5 | 37.9 | 17.8 | 248.7 |
| control_zone_workers | 100 | 100.0 | 20.5 | 40.2 | 72.6 | 23.9 | 20.1 | 13.1 | 203.8 |
| control_zones | 100 | 100.0 | 13.0 | 25.2 | 208.1 | 18.6 | 31.4 | 8.5 | 260.1 |
| users_me | 100 | 100.0 | 6.8 | 12.3 | 29.9 | 7.9 | 5.3 | 5.0 | 52.4 |

> 측정 환경/조건은 수집 시점 기준으로 별도 기재. 로컬 단일 노드, 순차 호출(동시성 1).

## 측정 환경/조건 (실측)

- 하드웨어: MacBook M2, 16GB RAM
- 백엔드 실행: Docker (이미지 `lookie-backend`), **linux/amd64 에뮬레이션**(호스트 arm64) — 네이티브 대비 지연 다소 큼(수치 해석 시 감안).
- DB/캐시: MySQL 8.0(compose), Redis 7-alpine(compose). 시드: `scripts/dummy-data-dashboard-test.sql`(users 57, zones 4, batches 2, batch_task_items 345).
- 호출: 각 엔드포인트 100회 **순차(동시성 1)**, curl `time_total` 기준. 로그인 계정: 시드 ADMIN(zone1).
- 관측성: Prometheus v2.53.0 + Grafana 11.1.0 + Micrometer, `/actuator/prometheus` 5s 스크레이프.
- 브랜치: `chore/pre-experiment-hygiene`. 측정 시점 커밋은 baseline 커밋 직전 HEAD.
- 주의: 콜드/워밍 구분 없이 100회 연속. 첫 호출 지연(max에 반영)은 JIT/캐시 워밍 영향 가능.
