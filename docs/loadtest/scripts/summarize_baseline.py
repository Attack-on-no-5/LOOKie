#!/usr/bin/env python3
"""baseline_raw/*.jsonl 을 읽어 엔드포인트별 응답시간 통계를 산출.

사용법:
    python3 docs/loadtest/scripts/summarize_baseline.py > docs/loadtest/BASELINE_METRICS.md

각 jsonl 라인: {"ts","http_code","time_total_s","bytes","body"}
출력: 엔드포인트별 count, 2xx 비율, p50/p95/p99(ms), mean/stddev/min/max(ms)
"""
import glob
import json
import os
import statistics
from datetime import datetime, timezone

RAW_DIR = os.path.join(os.path.dirname(__file__), "..", "baseline_raw")


def pct(sorted_vals, p):
    if not sorted_vals:
        return float("nan")
    k = (len(sorted_vals) - 1) * p
    lo = int(k)
    hi = min(lo + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def main():
    files = sorted(glob.glob(os.path.join(RAW_DIR, "*.jsonl")))
    print("# LOOKie Baseline Metrics\n")
    print(f"- 측정 시각(집계): {datetime.now(timezone.utc).isoformat()}")
    print(f"- 원시 데이터: `docs/loadtest/baseline_raw/*.jsonl` ({len(files)}개 엔드포인트)\n")
    print("| 엔드포인트 | N | 2xx% | p50(ms) | p95(ms) | p99(ms) | mean(ms) | stddev(ms) | min(ms) | max(ms) |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for f in files:
        slug = os.path.basename(f)[:-6]
        times_ms, ok = [], 0
        with open(f) as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                times_ms.append(float(row["time_total_s"]) * 1000.0)
                if 200 <= int(row["http_code"]) < 300:
                    ok += 1
        n = len(times_ms)
        if n == 0:
            print(f"| {slug} | 0 | - | - | - | - | - | - | - | - |")
            continue
        s = sorted(times_ms)
        std = statistics.pstdev(times_ms) if n > 1 else 0.0
        print(
            f"| {slug} | {n} | {100.0*ok/n:.1f} | {pct(s,0.5):.1f} | {pct(s,0.95):.1f} | "
            f"{pct(s,0.99):.1f} | {statistics.mean(times_ms):.1f} | {std:.1f} | {min(s):.1f} | {max(s):.1f} |"
        )
    print("\n> 측정 환경/조건은 수집 시점 기준으로 별도 기재. 로컬 단일 노드, 순차 호출(동시성 1).")


if __name__ == "__main__":
    main()
