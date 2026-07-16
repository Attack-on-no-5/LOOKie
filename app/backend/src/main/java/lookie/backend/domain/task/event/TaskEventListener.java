package lookie.backend.domain.task.event;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * TaskService에서 발행한 이벤트 후처리
 * - AFTER_COMMIT에만 실행됨
 *
 * NOTE: 진행률 카운터(:progress) 갱신 책임은 ControlEventListener 단독으로 일원화되었다.
 * 과거 이 리스너의 onTaskItemCompleted가 동일 TaskItemCompletedEvent를 함께 처리해
 * :progress.completed 가 이벤트당 +2로 이중 집계되던 문제가 있어 제거됨.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TaskEventListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskCompleted(TaskCompletedEvent event) {
        // 커밋 이후에만 실행되는지 확인하기 위한 로그
        log.info(
                "[AFTER_COMMIT] task completed. taskId={}, workerId={}, zoneId={}",
                event.getTaskId(), event.getWorkerId(), event.getZoneId());
        // TODO: 이후 관제/알림 확장
    }
}
