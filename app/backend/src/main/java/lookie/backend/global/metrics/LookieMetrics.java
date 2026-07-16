package lookie.backend.global.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 실험별 커스텀 메트릭 등록 유틸 (Phase 0).
 * 실험 A/B/C가 각각 필요한 지점에서 이 클래스를 주입받아 카운터·타이머를 호출한다.
 * - 이번 세션(Phase 0)에서는 정의만 하고, 호출부 삽입은 실험 단계에서 진행.
 */
@Component
public class LookieMetrics {

    private final MeterRegistry registry;

    public LookieMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ---- 실험 A: 이중 리스너 진행률 정합성 ----
    public void incrementZoneProgressListener(String listener, String result) {
        Counter.builder("lookie_zone_progress_listener_invocations_total")
                .tag("listener", listener)  // "task_event" or "control_event"
                .tag("result", result)      // "incremented" | "skipped_idempotent" | "reverted" | "skipped_dedup"
                .description("Zone progress listener invocations")
                .register(registry)
                .increment();
    }

    // ---- 실험 B: Hover 폴링 DB 부하 ----
    public Timer.Sample startHoverTimer() {
        return Timer.start(registry);
    }

    public void stopHoverTimer(Timer.Sample sample, String source) {
        sample.stop(Timer.builder("lookie_hover_query_duration_seconds")
                .tag("source", source)  // "db" or "redis"
                .description("Hover query duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
    }

    public void incrementHoverDbCalls() {
        Counter.builder("lookie_hover_db_calls_total")
                .description("Total Hover DB calls")
                .register(registry)
                .increment();
    }

    public void incrementHoverCacheHit() {
        Counter.builder("lookie_hover_cache_hits_total")
                .description("Total Hover Redis cache hits")
                .register(registry)
                .increment();
    }

    // ---- 실험 C: 관리자 배정 경합 ----
    public void incrementManagerSelectRetry() {
        Counter.builder("lookie_manager_select_retry_total")
                .description("Manager selection retry attempts")
                .register(registry)
                .increment();
    }

    public void incrementManagerDoubleAssign() {
        Counter.builder("lookie_manager_double_assign_total")
                .description("Detected duplicate manager assignments")
                .register(registry)
                .increment();
    }

    public void incrementManagerAtomicReserveResult(String result) {
        Counter.builder("lookie_manager_atomic_reserve_total")
                .tag("result", result)  // "acquired" or "failed"
                .description("Manager atomic reservation attempts")
                .register(registry)
                .increment();
    }
}
