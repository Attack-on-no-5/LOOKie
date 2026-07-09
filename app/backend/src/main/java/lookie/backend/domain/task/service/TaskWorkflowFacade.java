package lookie.backend.domain.task.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lookie.backend.domain.location.service.LocationService;
import lookie.backend.domain.location.vo.LocationVO;
import lookie.backend.domain.task.constant.NextAction;
import lookie.backend.domain.task.dto.TaskResponse;
import lookie.backend.domain.task.exception.InvalidTaskActionStateException;
import lookie.backend.domain.task.exception.NoAvailableTaskException;
import lookie.backend.domain.task.exception.TaskLocationMismatchException;
import lookie.backend.domain.task.exception.TaskNotFoundException;
import lookie.backend.domain.task.exception.TaskNotReleasableException;
import lookie.backend.domain.task.exception.WorkerAlreadyHasTaskException;
import lookie.backend.domain.task.event.TaskCompletedEvent;

import lookie.backend.domain.task.mapper.TaskMapper;
import lookie.backend.domain.batch.mapper.BatchMapper;
import lookie.backend.domain.task.vo.TaskActionStatus;
import lookie.backend.domain.task.vo.TaskItemVO;
import lookie.backend.domain.task.vo.TaskVO;
import lookie.backend.domain.tote.service.ToteService;
import lookie.backend.domain.tote.vo.ToteVO;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task 메인 워크플로우를 주관하는 Facade 서비스
 * - 집품 프로세스(할당~완료) 전체 흐름 제어
 * - 트랜잭션 경계 설정 및 도메인 서비스 간 오케스트레이션
 * - 다음 단계 행동(NextAction) 결정
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskWorkflowFacade {

    private final TaskMapper taskMapper;
    private final BatchMapper batchMapper;
    private final TaskItemService taskItemService;
    private final ToteService toteService;
    private final LocationService locationService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * [워크플로우 1단계] 작업 할당 및 시작
     * - 구역 내 대기 중인 작업을 작업자에게 배정하고 진행 상태로 전환합니다.
     */
    @Transactional
    public TaskResponse<TaskVO> startTask(Long workerId, Long zoneId) {
        // 작업자가 이미 진행 중인 작업이 있는지 확인
        TaskVO activeTask = taskMapper.findInProgressByWorkerId(workerId);
        if (activeTask != null) {
            throw new WorkerAlreadyHasTaskException();
        }

        TaskVO task = taskMapper.findNextUnassignedForZoneForUpdate(zoneId);
        if (task == null) {
            throw new NoAvailableTaskException(zoneId);
        }

        taskMapper.updateAssignToInProgress(task.getBatchTaskId(), workerId);
        TaskVO updatedTask = taskMapper.findById(task.getBatchTaskId());

        return TaskResponse.of(
                updatedTask,
                NextAction.SCAN_TOTE,
                null // 작업 시작 시점엔 아직 진행 할 상품 조회 불필요 (토트가 없으므로)
        );
    }

    /**
     * [워크플로우 2단계] 토트 바코드 스캔
     * - 작업에 사용할 토트를 스캔하고 작업과 매핑합니다.
     */
    @Transactional
    public TaskResponse<TaskVO> scanTote(Long taskId, String barcode) {
        TaskVO task = getTaskOrThrow(taskId);
        validateAction(task, TaskActionStatus.SCAN_TOTE);

        ToteVO tote = toteService.getByBarcode(barcode);
        toteService.validateToteAvailability(tote, taskId);

        taskMapper.updateToteScanResult(taskId, tote.getToteId());
        toteService.mappingToTask(tote.getToteId(), taskId);

        TaskItemVO nextItem = taskItemService.getNextItem(taskId);
        return TaskResponse.of(taskMapper.findById(taskId), NextAction.SCAN_LOCATION, nextItem);
    }

    /**
     * [워크플로우 3단계] 지번 바코드 스캔
     * - 집품할 상품이 있는 위치(Location)를 확인합니다.
     */
    @Transactional
    public TaskResponse<TaskVO> scanLocation(Long taskId, String locationCode) {
        log.info("[TaskWorkflow] scanLocation started. taskId={}, locationCode={}", taskId, locationCode);
        TaskVO task = getTaskOrThrow(taskId);

        validateAction(task, TaskActionStatus.SCAN_LOCATION);

        LocationVO location = locationService.getByCode(locationCode);
        locationService.validateZone(location, task.getZoneId());

        // 지번 검증: 다음 수행할 아이템의 지번과 일치하는지 확인 (순차 작업 강제)
        TaskItemVO nextItem = taskItemService.getNextItem(taskId);
        if (nextItem != null && !nextItem.getLocationId().equals(location.getLocationId())) {
            log.warn("[TaskWorkflow] Location mismatch. ExpectedId={}, ScannedId={}",
                    nextItem.getLocationId(), location.getLocationId());
            throw new TaskLocationMismatchException();
        }

        log.info("[TaskWorkflow] scanLocation success. taskId={}", taskId);
        taskMapper.updateLocationScanResult(taskId, location.getLocationId());

        return TaskResponse.of(taskMapper.findById(taskId), NextAction.SCAN_ITEM, nextItem);
    }

    /**
     * [워크플로우 4단계] 상품 바코드 스캔
     * - 상품을 식별하고 즉시 수량을 1 증가시킵니다. (기본 1개 집품 처리)
     */
    @Transactional
    public TaskResponse<TaskItemVO> scanItem(Long taskId, String barcode) {
        TaskVO task = getTaskOrThrow(taskId);
        validateAction(task, TaskActionStatus.SCAN_ITEM);

        TaskItemVO item = taskItemService.scanAndGetItem(taskId, task.getCurrentLocationId(), barcode);

        // 스캔 시 수량 자동 증가 없음 (0) - 사용자 수동 입력 유도
        TaskItemVO updatedItem = taskItemService.updateQuantityAtomic(item.getBatchTaskItemId(), 0);

        // 스캔 후에는 무조건 수량 조정 단계로 간주하고 DB 상태도 동기화
        taskMapper.updateActionStatus(taskId, TaskActionStatus.ADJUST_QUANTITY);
        NextAction nextAction = NextAction.ADJUST_QUANTITY;

        TaskItemVO nextItem = taskItemService.getNextItem(taskId);
        return TaskResponse.of(updatedItem, nextAction, nextItem);
    }

    /**
     * [워크플로우 5단계] 상품 집품(수량 조정)
     * - 상품을 집품하여 수량을 반영하고, 해당 상품 또는 지번의 완료 여부를 판단합니다.
     */
    @Transactional
    public TaskResponse<TaskItemVO> pickItem(Long itemId, int increment) {
        TaskItemVO updatedItem = taskItemService.updateQuantityAtomic(itemId, increment);

        // 수량 변경만 수행하고 상태 전이는 completeItem에서 담당
        return TaskResponse.of(updatedItem, NextAction.ADJUST_QUANTITY, null);
    }

    /**
     * [워크플로우 5-2단계] 상품 집품 완료 (수동)
     * - 프론트엔드에서 [완료] 버튼 클릭 시 호출
     */
    @Transactional
    public TaskResponse<TaskItemVO> completeItem(Long itemId) {
        TaskItemVO item = taskItemService.completeItemManual(itemId);

        // [Event] Facade layer publishes event with full context (zoneId, batchId)
        // This prevents listener from fetching Task/Zone again from DB
        TaskVO task = taskMapper.findById(item.getBatchTaskId());
        if (task != null) {
            eventPublisher.publishEvent(new lookie.backend.domain.task.event.TaskItemCompletedEvent(
                    item.getBatchTaskItemId(),
                    item.getBatchTaskId(),
                    task.getZoneId(),
                    task.getBatchId()));
        }

        NextAction nextAction = determineNextActionAfterPick(item);
        updateTaskStatusIfNecessary(item.getBatchTaskId(), nextAction);

        TaskItemVO nextItem = taskItemService.getNextItem(item.getBatchTaskId());
        return TaskResponse.of(item, nextAction, nextItem);
    }

    /**
     * [워크플로우 6단계] 전체 작업 완료
     * - 해당 작업 내 모든 상품의 집품이 완료되었을 때 작업을 최종 종료 처리합니다.
     */
    @Transactional
    public void completeTask(Long taskId) {
        TaskVO task = getTaskOrThrow(taskId);

        // 미완료 아이템이 있는지 검증
        int pendingCount = taskItemService.countPendingItems(taskId);
        if (pendingCount > 0) {
            throw new TaskNotReleasableException();
        }

        taskMapper.updateComplete(taskId);

        // 이벤트 발행 복구
        eventPublisher.publishEvent(new TaskCompletedEvent(
                task.getBatchTaskId(),
                task.getWorkerId(),
                task.getZoneId(),
                task.getBatchId()));

        // [Batch] 배치 내 모든 Task가 완료되었는지 확인하고 Batch 상태 업데이트
        int remainingTasks = taskMapper.countInProgressTasksByBatch(task.getBatchId());
        if (remainingTasks == 0) {
            log.info("[TaskWorkflow] All tasks completed for batch {}. Updating batch status to COMPLETED.",
                    task.getBatchId());
            batchMapper.updateStatus(task.getBatchId(), "COMPLETED");
        }
    }

    /**
     * [조회] 작업자의 현재 진행 중인 작업 조회 (화면 복구용)
     * - 로그인 직후나 새로고침 시 호출되어, 작업자가 하던 단계로 되돌아갈 수 있게 합니다.
     */
    @Transactional(readOnly = true)
    public TaskResponse<TaskVO> getInProgressTask(Long workerId) {
        TaskVO task = taskMapper.findInProgressByWorkerId(workerId);
        if (task == null) {
            return null; // 진행 중인 작업 없음
        }

        // 상세 정보 조회를 위해 재조회 (findInProgressByWorkerId는 일부 컬럼만 조회함)
        TaskVO fullTask = taskMapper.findById(task.getBatchTaskId());

        // 다음 진행할 아이템 조회 (없을 수도 있음)
        TaskItemVO nextItem = taskItemService.getNextItem(fullTask.getBatchTaskId());

        // 현재 DB 상태에 맞는 NextAction 유추
        NextAction nextAction = resolveNextAction(fullTask.getActionStatus());

        // [BugFix] 현재 지번과 다음 아이템의 지번이 다르면 SCAN_LOCATION으로 강제 변경
        if (nextItem != null) {
            Long currentLocId = fullTask.getCurrentLocationId();
            Long nextLocId = nextItem.getLocationId();

            // 현재 지번이 없거나(초기상태), 다음 아이템 지번과 다르면 -> 지번 스캔부터 해야 함
            if (currentLocId == null || !currentLocId.equals(nextLocId)) {
                nextAction = NextAction.SCAN_LOCATION;
                // 필요하다면 DB의 action status도 동기화할 수 있으나, 조회 메서드이므로 응답만 보정
            }
        }

        return TaskResponse.of(fullTask, nextAction, nextItem);
    }

    private TaskVO getTaskOrThrow(Long taskId) {
        TaskVO task = taskMapper.findById(taskId);
        if (task == null) {
            throw new TaskNotFoundException(taskId);
        }
        return task;
    }

    private void validateAction(TaskVO task, TaskActionStatus expected) {
        log.debug("[TaskWorkflow] Validating action. TaskId={}, DB_ActionStatus={}, Expected={}",
                task.getBatchTaskId(), task.getActionStatus(), expected);
        if (task.getActionStatus() != expected) {
            log.error("[TaskWorkflow] State Mismatch! TaskId={}, DB_ActionStatus={}, Expected={}",
                    task.getBatchTaskId(), task.getActionStatus(), expected);
            throw new InvalidTaskActionStateException(task.getActionStatus(), expected);
        }
    }

    private void updateTaskStatusIfNecessary(Long taskId, NextAction nextAction) {
        if (nextAction == NextAction.SCAN_LOCATION) {
            taskMapper.updateActionStatus(taskId, TaskActionStatus.SCAN_LOCATION);
        } else if (nextAction == NextAction.COMPLETE_TASK) {
            taskMapper.updateActionStatus(taskId, TaskActionStatus.COMPLETE_TASK);
        } else if (nextAction == NextAction.ADJUST_QUANTITY) {
            taskMapper.updateActionStatus(taskId, TaskActionStatus.ADJUST_QUANTITY);
        }
    }

    private NextAction determineNextActionAfterPick(TaskItemVO item) {
        log.debug("[TaskWorkflow] Determining next action for item {}. Status={}", item.getBatchTaskItemId(),
                item.getStatus());

        // DONE 또는 ISSUE_PENDING(이슈 보류) 상태면 해당 아이템 처리는 끝난 것으로 간주
        // ('ISSUE'는 V2602071200 FSM 재정의로 제거된 레거시 값이라 참조 삭제)
        if ("DONE".equals(item.getStatus())
                || "ISSUE_PENDING".equals(item.getStatus())) {
            int totalPending = taskItemService.countPendingItems(item.getBatchTaskId());
            log.info("[TaskWorkflow] Item Finished ({}). Total pending items for task {}: {}",
                    item.getStatus(), item.getBatchTaskId(), totalPending);

            if (totalPending == 0) {
                return NextAction.COMPLETE_TASK;
            }
            return NextAction.SCAN_LOCATION;
        }
        return NextAction.ADJUST_QUANTITY;
    }

    private NextAction resolveNextAction(TaskActionStatus status) {
        if (status == null)
            return NextAction.NONE;
        switch (status) {
            case SCAN_TOTE:
                return NextAction.SCAN_TOTE;
            case SCAN_LOCATION:
                return NextAction.SCAN_LOCATION;
            case SCAN_ITEM:
                return NextAction.SCAN_ITEM;
            case ADJUST_QUANTITY:
                return NextAction.ADJUST_QUANTITY;
            case COMPLETE_TASK:
                return NextAction.COMPLETE_TASK;
            default:
                return NextAction.NONE;
        }
    }
}
