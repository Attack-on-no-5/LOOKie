package lookie.backend.domain.issue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lookie.backend.domain.task.event.TaskItemCompletedEvent;
import lookie.backend.domain.task.event.TaskItemRevertedEvent;
import lookie.backend.domain.inventory.service.InventoryService;
import lookie.backend.domain.issue.dto.AiResultRequest;
import lookie.backend.domain.issue.dto.AiResultResponse;
import lookie.backend.infra.ai.AiAnalysisClient;
import lookie.backend.infra.ai.dto.AiAnalysisRequest;
import lookie.backend.domain.issue.dto.CreateIssueRequest;
import lookie.backend.domain.issue.dto.IssueDetailResponse;
import lookie.backend.domain.issue.dto.IssueNextAction;
import lookie.backend.domain.issue.dto.WorkerNextAction;
import lookie.backend.domain.issue.dto.AdminNextAction;
import lookie.backend.domain.issue.dto.AdminIssueListRequest;

import lookie.backend.domain.issue.dto.AdminIssueListResponse;
import lookie.backend.domain.issue.dto.AdminIssueSummary;
import lookie.backend.domain.issue.dto.IssueResponse;
import lookie.backend.domain.issue.mapper.IssueMapper;
import lookie.backend.domain.issue.vo.AiJudgmentVO;
import lookie.backend.domain.issue.vo.IssueImageVO;
import lookie.backend.domain.issue.vo.IssueVO;
import lookie.backend.domain.task.mapper.TaskMapper;
import lookie.backend.domain.task.service.TaskItemService;
import lookie.backend.domain.task.vo.TaskItemVO;
import lookie.backend.domain.task.vo.TaskVO;
import lookie.backend.global.error.ApiException;
import lookie.backend.global.error.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lookie.backend.domain.issue.dto.AdminDecision;

import lookie.backend.domain.issue.dto.IssueStatus;
import lookie.backend.domain.issue.dto.MyIssueSummary;

/**
 * Issue 도메인 비즈니스 로직 처리 서비스
 * - 이슈 생성 (AI 판정 요청 성공 시)
 * - AI 판정 결과 반영 (Webhook 수신 시)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueService {

    private final IssueMapper issueMapper;
    private final TaskItemService taskItemService;
    private final TaskMapper taskMapper;
    private final AiAnalysisClient aiAnalysisClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final InventoryService inventoryService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * [WebRTC] 화상 연결 전 이슈 상태 검증
     * - Task가 이미 완료(Next Task 진행 중)되었다면 연결 차단
     *
     * @throws ApiException(ISSUE_TASK_ALREADY_DONE)
     */
    @Transactional(readOnly = true)
    public void validateIssueForCall(Long issueId) {
        if (issueId == null)
            return;

        IssueVO issue = issueMapper.findById(issueId);
        if (issue == null) {
            throw new ApiException(ErrorCode.ISSUE_NOT_FOUND);
        }

        // 1. Task 상태 조회
        TaskVO task = taskMapper.findById(issue.getBatchTaskId());
        if (task == null) {
            return; // Task가 없으면 검증 패스 (예외 상황)
        }

        // 2. Task가 이미 DONE(완료) 상태라면 호출 차단
        if ("DONE".equals(task.getStatus())) {
            throw new ApiException(ErrorCode.ISSUE_TASK_ALREADY_DONE);
        }
    }

    // AI 판정 요청 성공 시 호출
    @Transactional
    public IssueResponse createIssue(Long workerId, CreateIssueRequest request) {
        log.info("[IssueService] createIssue started. workerId={}, itemId={}",
                workerId, request.getTaskItemId());

        // 1. TaskItem 존재 여부 확인
        TaskItemVO item = taskItemService.getTaskItem(request.getTaskItemId());
        if (item == null) {
            throw new ApiException(ErrorCode.ISSUE_ITEM_NOT_FOUND);
        }

        // 2. 작업자 권한 검증
        TaskVO task = taskMapper.findById(item.getBatchTaskId());
        if (task == null || !workerId.equals(task.getWorkerId())) {
            log.warn("[IssueService] Unauthorized issue creation attempt. workerId={}, taskWorkerId={}",
                    workerId, task != null ? task.getWorkerId() : "null");
            throw new ApiException(ErrorCode.ISSUE_TASK_NOT_ASSIGNED);
        }

        // 3. Issue 생성
        IssueVO issue = IssueVO.createInitial(workerId, item, request.getIssueType());

        if ("DAMAGED".equals(request.getIssueType())) {
            if (request.getImageUrl() == null || request.getImageUrl().isEmpty()) {
                throw new ApiException(ErrorCode.ISSUE_IMAGE_REQUIRED); // 이미지가 반드시 필요함
            }
        }

        issueMapper.insertIssue(issue);
        log.info("[IssueService] Issue created. issueId={}", issue.getIssueId());

        // 4. 이슈 증빙 이미지 저장 (이미지가 있는 경우)
        if (request.getImageUrl() != null && !request.getImageUrl().isEmpty()) {
            IssueImageVO image = new IssueImageVO();
            image.setIssueId(issue.getIssueId());
            image.setImageUrl(request.getImageUrl());
            issueMapper.insertIssueImage(image);
        }

        // 5. AI 판정 초기 데이터 생성 (UNKNOWN 상태)
        AiJudgmentVO judgment = new AiJudgmentVO();
        judgment.setIssueId(issue.getIssueId());
        judgment.setImageUrl(request.getImageUrl()); // 이미지 있으면 저장
        judgment.setAiDecision("UNKNOWN"); // AI 결과 대기 중
        issueMapper.insertAiJudgment(judgment);

        // 6. TaskItem 상태를 ISSUE_PENDING(보류)으로 변경
        taskItemService.markAsIssuePending(item.getBatchTaskItemId());

        // [Inventory] 파손 이슈인 경우 재고 임시 차감 (Available -> DamagedTemp)
        if ("DAMAGED".equals(request.getIssueType())) {
            inventoryService.recordEvent(
                    "PICK_DAMAGED_TEMP",
                    item.getProductId(),
                    item.getLocationId(),
                    -1, // 1개 임시 차감 (Available -1, DamagedTemp +1)
                    "ISSUE",
                    issue.getIssueId(),
                    workerId);
            log.info("[IssueService] PICK_DAMAGED_TEMP event recorded for new issue. issueId={}", issue.getIssueId());
        }

        // [Event] ISSUE_PENDING도 실시간 집계에서는 '완료'와 유사하게 처리 (할일 목록 제외)
        if (task != null) {
            eventPublisher.publishEvent(new TaskItemCompletedEvent(
                    item.getBatchTaskItemId(),
                    item.getBatchTaskId(),
                    task.getZoneId(),
                    task.getBatchId()));
        }

        log.info("[IssueService] TaskItem marked as ISSUE_PENDING and event published. itemId={}",
                item.getBatchTaskItemId());

        IssueResponse response = IssueResponse.from(issue);

        // 7. AI 분석 요청 (트랜잭션 커밋 후 비동기 실행)
        // DAMAGED는 이미지 분석, OOS는 재고 확인 등으로 활용 가능
        if (request.getImageUrl() != null || "OUT_OF_STOCK".equals(request.getIssueType())) {
            Long issueIdForAi = issue.getIssueId();
            String issueType = request.getIssueType();
            String imageUrl = request.getImageUrl();

            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            requestAiAnalysisSafe(issueIdForAi, item, issueType, imageUrl);
                        } catch (Exception e) {
                            log.error("Failed to request AI analysis", e);
                        }
                    }
                });
            } else {
                requestAiAnalysisSafe(issueIdForAi, item, issueType, imageUrl);
            }
        }

        return response;
    }

    /**
     * AI 분석 요청 (트랜잭션 없음 - 커밋 후 실행됨)
     */
    public void requestAiAnalysisSafe(Long issueId, TaskItemVO item, String issueType, String imageUrl) {
        AiAnalysisRequest aiRequest = buildAiAnalysisRequest(issueId, item, issueType, imageUrl);
        aiAnalysisClient.requestAnalysis(aiRequest);
    }

    /**
     * 이슈 타입에 따라 적절한 AI 분석 요청 생성
     */
    private AiAnalysisRequest buildAiAnalysisRequest(
            Long issueId,
            TaskItemVO item,
            String issueType,
            String imageUrl) {

        // DAMAGED 케이스: 이미지만 전달
        if ("DAMAGED".equals(issueType)) {
            return AiAnalysisRequest.create(
                    issueId,
                    item.getProductId(),
                    issueType,
                    imageUrl,
                    null);
        }

        // OUT_OF_STOCK 케이스: 재고 상태 조회 및 전달
        if ("OUT_OF_STOCK".equals(issueType)) {
            // 재고 상태 조회
            Map<String, Object> inventoryState = inventoryService.getInventoryState(
                    item.getProductId(),
                    item.getLocationId());

            // InventoryStateDto 생성
            lookie.backend.infra.ai.dto.InventoryStateDto inventoryStateDto = lookie.backend.infra.ai.dto.InventoryStateDto
                    .builder()
                    .availableQty((Integer) inventoryState.get("availableQty"))
                    .damagedTempQty((Integer) inventoryState.get("damagedTempQty"))
                    .scannedLocation(item.getLocationCode())
                    .expectedLocation(item.getLocationCode())
                    .lastEventType((String) inventoryState.get("lastEventType"))
                    .build();

            log.info("[IssueService] OUT_OF_STOCK detected. inventoryState={}", inventoryStateDto);

            return AiAnalysisRequest.create(
                    issueId,
                    item.getProductId(),
                    issueType,
                    null, // OUT_OF_STOCK은 이미지 없음
                    inventoryStateDto);
        }

        // 기타 타입은 기본 처리 (DAMAGED 방식)
        log.warn("[IssueService] Unknown issue type: {}. Using DAMAGED default.", issueType);
        return AiAnalysisRequest.create(
                issueId,
                item.getProductId(),
                issueType,
                imageUrl,
                null);
    }

    /**
     * AI 재분석 요청 (재촬영)
     */
    @Transactional
    public void retakeIssue(Long workerId, Long issueId, String imageUrl) {
        log.info("[IssueService] retakeIssue started. workerId={}, issueId={}", workerId, issueId);

        // 1. Issue 조회
        IssueVO issue = issueMapper.findById(issueId);
        if (issue == null) {
            throw new ApiException(ErrorCode.ISSUE_NOT_FOUND);
        }

        // 2. 권한 검증
        if (!workerId.equals(issue.getWorkerId())) {
            log.warn("[IssueService] Unauthorized retake attempt. workerId={}, issueWorkerId={}",
                    workerId, issue.getWorkerId());
            throw new ApiException(ErrorCode.ISSUE_TASK_NOT_ASSIGNED);
        }

        // 3. AI 판정 상태 확인
        AiJudgmentVO judgment = issueMapper.findAiJudgmentByIssueId(issueId);
        if (judgment == null) {
            throw new ApiException(ErrorCode.ISSUE_NOT_FOUND);
        }

        log.info("[IssueService] Retaking issue. Original decision={}", judgment.getAiDecision());

        // 4. 새 이미지 이력 저장
        IssueImageVO image = new IssueImageVO();
        image.setIssueId(issueId);
        image.setImageUrl(imageUrl);
        issueMapper.insertIssueImage(image);
        log.info("[IssueService] New image saved for retake.", imageUrl);

        // 5. AI 판정 상태 리셋 (UNKNOWN)
        judgment.setImageUrl(imageUrl);
        judgment.setAiDecision("UNKNOWN");
        judgment.setConfidence(null);
        judgment.setAiResult(null);
        judgment.setSummary(null);

        issueMapper.updateAiJudgment(judgment);
        log.info("[IssueService] AiJudgment reset to UNKNOWN for retake.");

        // 6. TaskItemVO 조회 (ProductId 필요)
        TaskItemVO item = taskItemService.getTaskItem(issue.getBatchTaskItemId());

        // 7. [Fix] AI 재요청도 트랜잭션 커밋 후에 실행 (또는 즉시 실행)
        Runnable retakeTask = () -> {
            try {
                log.info("[IssueService] Transaction committed (or mocked). Sending Retake request for issueId={}",
                        issueId);
                AiAnalysisRequest aiRequest = AiAnalysisRequest.of(
                        issueId,
                        item != null ? item.getProductId() : 0L,
                        imageUrl);
                aiAnalysisClient.requestAnalysis(aiRequest);
            } catch (Exception e) {
                log.error("[IssueService] Failed to request Retake analysis. issueId={}", issueId, e);
            }
        };

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    retakeTask.run();
                }
            });
        } else {
            retakeTask.run();
        }
    }

    /**
     * 이슈 상세 조회
     * - Issue 기본 정보 + AI 판정 결과 + 계산 필드 반환
     * - 프론트엔드가 이슈 상태를 확인할 수 있도록 조회 전용 API
     */
    @Transactional(readOnly = true)
    public IssueDetailResponse getIssueDetail(Long issueId) {
        log.info("[IssueService] getIssueDetail started. issueId={}", issueId);

        // 1. Issue 조회
        IssueVO issue = issueMapper.findById(issueId);
        if (issue == null) {
            throw new ApiException(ErrorCode.ISSUE_NOT_FOUND);
        }

        // 2. AI 판정 결과 조회
        AiJudgmentVO judgment = issueMapper.findAiJudgmentByIssueId(issueId);

        // 3. nextAction 계산 (분기표 기준)
        WorkerNextAction workerNextAction = calculateWorkerNextAction(issue, judgment);
        IssueNextAction issueNextAction = calculateIssueNextAction(issue, judgment);
        AdminNextAction adminNextAction = calculateAdminNextAction(issue, judgment);

        // 4. availableActions 생성
        List<String> availableActions = generateAvailableActions(issue);

        // 5. DTO 매핑 및 반환
        return IssueDetailResponse.from(
                issue,
                judgment,
                workerNextAction != null ? workerNextAction.name() : null,
                issueNextAction != null ? issueNextAction.name() : null,
                adminNextAction != null ? adminNextAction.name() : null,
                availableActions);
    }

    /**
     * AI 판정 결과 반영
     * - AI 서버로부터 판정 결과 수신 시 호출
     * - AI 결과에 따라 Issue 정책 자동 업데이트
     * - reasonCode와 newLocation 정보 처리
     */
    @Transactional
    public AiResultResponse processAiResult(Long issueId, AiResultRequest request) {
        log.info("[IssueService] processAiResult started. issueId={}, aiDecision={}, reasonCode={}",
                issueId, request.getAiDecision(), request.getReasonCode());

        // [Reason Code Mapping] AI 서버 응답값과 DB ENUM 매핑
        // DB ENUM: DAMAGED, OUT_OF_STOCK, STOCK_EXISTS, MOVE_LOCATION, WAITING_RETURN,
        // UNKNOWN, AUTO_RESOLVED
        if ("NORMAL".equals(request.getReasonCode()) || "AMBIGUOUS".equals(request.getReasonCode())) {
            log.warn("[IssueService] Mapping AI reasonCode {} to UNKNOWN", request.getReasonCode());
            request.setReasonCode("UNKNOWN");
        }

        // 0. Confidence == 0.0 처리 (신뢰 불가 → NEED_CHECK 강제)
        String decision = request.getAiDecision();
        if (request.getConfidence() != null && request.getConfidence() == 0.0f) {
            decision = "NEED_CHECK";
            request.setAiDecision(decision); // DB 업데이트를 위해 request 객체 수정
            log.warn("[IssueService] Confidence is 0.0. Forced decision to NEED_CHECK. issueId={}", issueId);
        }

        // 1. Issue 존재 확인
        IssueVO issue = issueMapper.findById(issueId);
        if (issue == null) {
            throw new ApiException(ErrorCode.ISSUE_NOT_FOUND);
        }

        // 이미 판정 결과가 존재하면(UNKNOWN이 아니면) 중복 업데이트 방지
        AiJudgmentVO existing = issueMapper.findAiJudgmentByIssueId(issueId);
        if (existing != null && !"UNKNOWN".equals(existing.getAiDecision())) {
            log.warn("[IssueService] AI result already applied. Skip overwrite. issueId={}, existingDecision={}",
                    issueId, existing.getAiDecision());
            return AiResultResponse.from(issue, calculateNextAction(issue).name(),
                    generateAvailableActions(issue),
                    existing.getAiDecision(), existing.getSummary(),
                    existing.getConfidence(), existing.getAiResult(), existing.getImageUrl());
        }

        // 2. AI 판정 결과 업데이트
        AiJudgmentVO judgment = new AiJudgmentVO();
        judgment.setIssueId(issueId);
        judgment.setAiDecision(request.getAiDecision());
        judgment.setConfidence(request.getConfidence() != null ? request.getConfidence().floatValue() : null);
        judgment.setSummary(request.getSummary());
        judgment.setAiResult(request.getAiResult());

        // [Fix] 기존 이미지 URL 유지 (재촬영 등으로 이미지가 업데이트된 경우 보존)
        if (existing != null) {
            judgment.setImageUrl(existing.getImageUrl());
        }

        issueMapper.updateAiJudgment(judgment);

        // 3. newLocation 처리 (MOVE_LOCATION 케이스)
        // newLocation이 String(locationCode)인 경우 locationId로 변환 필요
        if ("MOVE_LOCATION".equals(request.getReasonCode()) && request.getNewLocation() != null) {
            // TODO: newLocation이 locationCode(String)라면 locationId로 변환 필요
            // 임시로 null 처리 (실제로는 LocationService로 변환)
            log.warn("[IssueService] MOVE_LOCATION received but newLocation conversion not implemented. newLocation={}",
                    request.getNewLocation());
            // issue.setNewLocationId(convertedLocationId);
        }

        // 4. AI 결과 → Issue 정책 매핑
        applyAiResultPolicy(issue, request.getAiDecision(), request.getReasonCode());

        // 5. Issue 업데이트
        issueMapper.updateIssueStatus(issue);
        log.info("[IssueService] Issue updated by AI result. issueId={}, status={}, reasonCode={}",
                issueId, issue.getStatus(), issue.getReasonCode());

        // [NEW] TaskItem 상태 동기화 (부활 vs 완료 vs 보류)
        syncTaskItemStatus(issue, request.getAiDecision(), request.getReasonCode());

        // 6. nextAction 계산
        IssueNextAction issueNextAction = calculateNextAction(issue);
        List<String> availableActions = generateAvailableActions(issue);

        // [Fix] Response에 이미지 URL 포함
        AiResultResponse response = AiResultResponse.from(issue, issueNextAction.name(), availableActions,
                judgment.getAiDecision(), judgment.getSummary(),
                judgment.getConfidence(), judgment.getAiResult(), judgment.getImageUrl());

        // [WebSocket] AI 판정 결과 통보
        messagingTemplate.convertAndSend("/topic/issues/" + issueId, response);
        log.info("[IssueService] AI result signaled via WebSocket. issueId={}", issueId);

        return response;
    }

    /**
     * AI 결과/관리자 조치에 따라 TaskItem 상태(부활/완료/유지) 동기화
     */
    private void syncTaskItemStatus(IssueVO issue, String aiDecision, String reasonCode) {
        Long itemId = issue.getBatchTaskItemId();

        // 1. 지번 이동 (MOVE_LOCATION) -> 지번 변경 후 부활
        if ("MOVE_LOCATION".equals(reasonCode)) {
            taskItemService.updateItemLocation(itemId, issue.getNewLocationId());
            // reviveItem 내부에서 Reverted 이벤트(-1)를 자체 발행하므로 별도 발행 제거(이중 차감 방지)
            taskItemService.reviveItem(itemId);

            log.info("[IssueService] Item Location Updated & Revived. itemId={}", itemId);
            return;
        }

        // 2. 정상 (PASS) or 자동 해결 (AUTO_RESOLVED) -> 부활
        // D1(PASS) 케이스: 관리자 사후 확인이 필요하지만(NON_BLOCKING), 작업자는 계속 진행(CONTINUE_PICKING)해야
        // 함.
        if ("PASS".equals(aiDecision) || "AUTO_RESOLVED".equals(reasonCode)) {
            // reviveItem 내부에서 Reverted 이벤트(-1)를 자체 발행하므로 별도 발행 제거(이중 차감 방지)
            taskItemService.reviveItem(itemId);

            log.info("[IssueService] Item Revived (PASS/AUTO). itemId={}", itemId);
            return;
        }

        // 3. 파손 확정 (FAIL) -> 상태 유지 (가이드 준수: DONE 확정은 관리자만)
        if ("FAIL".equals(aiDecision) || "DAMAGED".equals(reasonCode)) {
            log.info("[IssueService] Item remains ISSUE_PENDING for admin confirmation. itemId={}", itemId);
            return;
        }

        // 4. 그 외 (NEED_CHECK, RETAKE, STOCK_EXISTS 등)
        // -> ISSUE_PENDING 상태 유지 (관리자 연결 대기 or 재촬영 대기)
    }

    /**
     * AI 판정 결과에 따른 Issue 정책 매핑
     * - issue_type, ai_decision, reasonCode 조합으로 정책 결정
     * 
     * @param issue      Issue VO (업데이트할 객체)
     * @param aiDecision AI 판정 결과 (PASS, FAIL, NEED_CHECK, RETAKE)
     * @param reasonCode AI 세부 분류 (STOCK_EXISTS, MOVE_LOCATION, WAITING_RETURN,
     *                   DAMAGED, UNKNOWN)
     */
    private void applyAiResultPolicy(IssueVO issue, String aiDecision, String reasonCode) {
        issue.setAiDecision(aiDecision); // [Fix] AI 판정 결과 직접 저장 (관리자 목록 조회용)
        String issueType = issue.getIssueType();

        // DAMAGED 케이스
        if ("DAMAGED".equals(issueType)) {
            applyDamagedPolicy(issue, aiDecision);
        }
        // OUT_OF_STOCK 케이스
        else if ("OUT_OF_STOCK".equals(issueType)) {
            applyOutOfStockPolicy(issue, aiDecision, reasonCode);
        } else {
            log.warn("[IssueService] Unknown issue_type={}. Applying default policy.", issueType);
            applyDefaultPolicy(issue);
        }
    }

    /**
     * DAMAGED 타입 정책 (분기표 D1, D5, D8, D12 노드 기준)
     */
    private void applyDamagedPolicy(IssueVO issue, String aiDecision) {
        switch (aiDecision) {
            case "PASS":
                // D1: 정상 판정 - OPEN 유지, 관리자 사후 확정 필요
                issue.setStatus("OPEN");
                issue.setUrgency(4);
                issue.setIssueHandling("NON_BLOCKING");
                issue.setAdminRequired(true);
                issue.setReasonCode("UNKNOWN");
                log.info("[IssueService] DAMAGED + PASS → OPEN, urgency=4, adminRequired=true. issueId={}",
                        issue.getIssueId());
                break;

            case "NEED_CHECK":
                // D5: 애매함 - BLOCKING, 관리자 필수 연결
                issue.setStatus("OPEN");
                issue.setUrgency(1);
                issue.setIssueHandling("BLOCKING");
                issue.setAdminRequired(true);
                issue.setReasonCode("UNKNOWN");
                log.info("[IssueService] DAMAGED + NEED_CHECK → BLOCKING, urgency=1. issueId={}",
                        issue.getIssueId());
                break;

            case "FAIL":
                // D8: 파손 가능성 - NON_BLOCKING, 사후 확정 필요
                issue.setStatus("OPEN");
                issue.setUrgency(3);
                issue.setIssueHandling("NON_BLOCKING");
                issue.setAdminRequired(true);
                issue.setReasonCode("DAMAGED");
                log.info("[IssueService] DAMAGED + FAIL → urgency=3, adminRequired=true. issueId={}",
                        issue.getIssueId());
                break;

            case "RETAKE":
                // D12: 재촬영 필요 - BLOCKING, 관리자 개입 없음, 관제 미노출
                issue.setStatus("OPEN");
                issue.setUrgency(0); // 관제 큐 제외
                issue.setIssueHandling("BLOCKING");
                issue.setAdminRequired(false);
                issue.setReasonCode("UNKNOWN");
                log.info("[IssueService] DAMAGED + RETAKE → BLOCKING, urgency=0 (queue excluded). issueId={}",
                        issue.getIssueId());
                break;

            case "UNKNOWN":
            default:
                applyDefaultPolicy(issue);
                break;
        }
    }

    /**
     * OUT_OF_STOCK 타입 정책 (분기표 S1, S4, S5, S6 노드 기준)
     * 
     * AI가 reasonCode를 직접 반환하므로, aiDecision + reasonCode 조합으로 정책 적용
     */
    private void applyOutOfStockPolicy(IssueVO issue, String aiDecision, String reasonCode) {
        // reasonCode가 없으면 기본 정책 적용
        if (reasonCode == null || "UNKNOWN".equals(reasonCode)) {
            applyDefaultPolicy(issue);
            return;
        }

        switch (reasonCode) {
            case "STOCK_EXISTS":
                // S1: 전산상 재고 있음 - BLOCKING, 관리자 필수
                issue.setStatus("OPEN");
                issue.setUrgency(1);
                issue.setIssueHandling("BLOCKING");
                issue.setAdminRequired(true);
                issue.setReasonCode("STOCK_EXISTS");
                log.info("[IssueService] OUT_OF_STOCK + STOCK_EXISTS → BLOCKING, urgency=1. issueId={}",
                        issue.getIssueId());
                break;

            case "MOVE_LOCATION":
                // S4: 지번 이동 - 즉시 RESOLVED
                issue.setStatus("RESOLVED");
                issue.setUrgency(5);
                issue.setIssueHandling("NON_BLOCKING");
                issue.setAdminRequired(false);
                issue.setReasonCode("MOVE_LOCATION");
                issue.setResolvedAt(java.time.LocalDateTime.now());
                // newLocationId는 processAiResult에서 설정됨
                log.info("[IssueService] OUT_OF_STOCK + MOVE_LOCATION → RESOLVED, urgency=5. issueId={}",
                        issue.getIssueId());
                break;

            case "WAITING_RETURN":
                // S5: 원복 대기 - NON_BLOCKING
                issue.setStatus("OPEN");
                issue.setUrgency(3);
                issue.setIssueHandling("NON_BLOCKING");
                issue.setAdminRequired(false);
                issue.setReasonCode("WAITING_RETURN");
                log.info("[IssueService] OUT_OF_STOCK + WAITING_RETURN → urgency=3. issueId={}",
                        issue.getIssueId());
                break;

            case "DAMAGED":
                // S6: 파손 원인 - NON_BLOCKING
                issue.setStatus("OPEN");
                issue.setUrgency(3);
                issue.setIssueHandling("NON_BLOCKING");
                issue.setAdminRequired(false);
                issue.setReasonCode("DAMAGED");
                log.info("[IssueService] OUT_OF_STOCK + DAMAGED → urgency=3. issueId={}",
                        issue.getIssueId());
                break;

            default:
                applyDefaultPolicy(issue);
                break;
        }
    }

    /**
     * 기본 정책 (UNKNOWN 등)
     */
    private void applyDefaultPolicy(IssueVO issue) {
        issue.setStatus("OPEN");
        issue.setIssueHandling("NON_BLOCKING");
        issue.setAdminRequired(false);
        issue.setReasonCode("UNKNOWN");
        log.warn("[IssueService] Applying default policy. issueId={}", issue.getIssueId());
    }

    /**
     * Issue 상태 조합으로 nextAction 계산
     * 
     * @param issue Issue VO
     * @return 프론트엔드 권고 행동
     */
    private IssueNextAction calculateNextAction(IssueVO issue) {
        // [140 가이드 반영] adminRequired가 true이면 항상 WAIT_ADMIN
        if (Boolean.TRUE.equals(issue.getAdminRequired())) {
            return IssueNextAction.WAIT_ADMIN;
        }

        // BLOCKING → WAIT_ADMIN (adminRequired가 false인데 BLOCKING인 경우 대비한 백업)
        if ("BLOCKING".equals(issue.getIssueHandling())) {
            return IssueNextAction.WAIT_ADMIN;
        }

        // RESOLVED + MOVE_LOCATION → MOVE_LOCATION
        if ("RESOLVED".equals(issue.getStatus()) && "MOVE_LOCATION".equals(issue.getReasonCode())) {
            return IssueNextAction.MOVE_LOCATION;
        }

        // RESOLVED + AUTO_RESOLVED → AUTO_RESOLVED
        if ("RESOLVED".equals(issue.getStatus()) && "AUTO_RESOLVED".equals(issue.getReasonCode())) {
            return IssueNextAction.AUTO_RESOLVED;
        }

        // 나머지 → NEXT_ITEM
        return IssueNextAction.NEXT_ITEM;
    }

    /**
     * 이슈 상태에 따른 가능한 액션 목록 생성
     * UI 힌트용으로만 사용되며 서버 명령이 아님
     * 
     * @param issue Issue VO
     * @return 가능한 액션 목록
     */
    private List<String> generateAvailableActions(IssueVO issue) {
        List<String> actions = new ArrayList<>();

        // adminRequired가 true이면 관리자 연결 가능
        if (Boolean.TRUE.equals(issue.getAdminRequired())) {
            actions.add("CONNECT_ADMIN");
        }

        // 그 외의 경우 빈 배열 반환
        return actions;
    }

    /**
     * 작업자 다음 행동 계산 (분기표 WorkerNextAction 컬럼 기준)
     */
    private WorkerNextAction calculateWorkerNextAction(IssueVO issue, AiJudgmentVO judgment) {
        // [NEW] 아이템이 PENDING(부활됨) 상태인지 확인
        // PENDING이면 무조건 하던 작업 계속 (CONTINUE_PICKING)
        TaskItemVO item = taskItemService.getTaskItem(issue.getBatchTaskItemId());
        if (item != null && "PENDING".equals(item.getStatus())) {
            return WorkerNextAction.CONTINUE_PICKING;
        }

        // [수정] BLOCKING 상태면 최우선으로 WAIT_ADMIN (재고있음 등 관리자 필수 케이스)
        // 관리자와 먼저 연결을 시도하고, 실패 시 NON_BLOCKING으로 바뀌면 그때 사진/다음단계 진행
        if ("BLOCKING".equals(issue.getIssueHandling())) {
            return WorkerNextAction.WAIT_ADMIN;
        }

        // RETAKE 케이스
        if (judgment != null && "RETAKE".equals(judgment.getAiDecision())) {
            return WorkerNextAction.UPLOAD_IMAGE;
        }

        // 이미지가 필요한 상황인지 체크 (OUT_OF_STOCK + AdminRequired + NoImage)
        // NOTE: BLOCKING이 아닐 때(예: 관리자 부재로 넘어감) 이미지가 없으면 증빙 촬영 요구
        if (isImageRequiredButMissing(issue)) {
            return WorkerNextAction.UPLOAD_REPORT_IMAGE;
        }

        // MOVE_LOCATION 케이스 (이미 PENDING 체크에서 걸러지겠지만, 안전장치)
        if ("RESOLVED".equals(issue.getStatus()) && "MOVE_LOCATION".equals(issue.getReasonCode())) {
            return WorkerNextAction.MOVE_LOCATION;
        }

        // 기본값: NEXT_ITEM (아이템이 PENDING이 아니므로 죽은 상태)
        return WorkerNextAction.NEXT_ITEM;
    }

    private boolean isImageRequiredButMissing(IssueVO issue) {
        // OUT_OF_STOCK 이슈이고 관리자 확인 필요(adminRequired=true) 상태인데
        // 이미지가 등록되지 않았다면 사진 업로드 필요
        if ("OUT_OF_STOCK".equals(issue.getIssueType()) && Boolean.TRUE.equals(issue.getAdminRequired())) {
            // 이미지 존재 여부 확인 (AiJudgment가 있어도 RETAKE 등으로 인해 이미지가 없을 수 있으므로 직접 체크 권장하나
            // 성능상 issue_images 조회가 부담스러우면 AiJudgment의 imageUrl 확인)
            AiJudgmentVO judgment = issueMapper.findAiJudgmentByIssueId(issue.getIssueId());
            // judgment가 없거나(초기), judgment에 이미지가 없으면 업로드 필요
            // 하지만 OUT_OF_STOCK은 초기 AI 분석 시 이미지가 없을 수 있음.
            // 정확히는 "현재 유효한 증빙 이미지가 있는가"를 체크해야 함.
            // 여기서는 간단히 "AiJudgment에 이미지가 없는 경우"를 미등록으로 간주
            // (createIssue에서 이미지 없이 생성하면 judgment.imageUrl도 null로 들어감)
            return judgment == null || judgment.getImageUrl() == null || judgment.getImageUrl().isEmpty();
        }
        return false;
    }

    @Transactional
    public void reportImage(Long workerId, Long issueId, String imageUrl) {
        log.info("[IssueService] reportImage started. workerId={}, issueId={}", workerId, issueId);

        IssueVO issue = issueMapper.findById(issueId);
        if (issue == null) {
            throw new ApiException(ErrorCode.ISSUE_NOT_FOUND);
        }

        // 권한 체크
        if (!workerId.equals(issue.getWorkerId())) {
            throw new ApiException(ErrorCode.ISSUE_TASK_NOT_ASSIGNED);
        }

        // 이미지 저장
        IssueImageVO image = new IssueImageVO();
        image.setIssueId(issue.getIssueId());
        image.setImageUrl(imageUrl);
        issueMapper.insertIssueImage(image);

        // AiJudgment에도 이미지 URL 업데이트 (관제에서 보기 위함, 재분석 X)
        AiJudgmentVO judgment = issueMapper.findAiJudgmentByIssueId(issueId);
        if (judgment != null) {
            judgment.setImageUrl(imageUrl);
            issueMapper.updateAiJudgment(judgment);
        }

        log.info("[IssueService] Report image saved. issueId={}", issueId);
    }

    /**
     * 이슈 다음 행동 계산 (분기표 IssueNextAction 컬럼 기준)
     */
    private IssueNextAction calculateIssueNextAction(IssueVO issue, AiJudgmentVO judgment) {
        // 이미지가 필요한 상황인지 체크
        if (isImageRequiredButMissing(issue)) {
            return IssueNextAction.WAIT_REPORT_IMAGE;
        }

        // RETAKE 케이스
        if (judgment != null && "RETAKE".equals(judgment.getAiDecision())) {
            return IssueNextAction.WAIT_RETAKE;
        }

        // BLOCKING 상태면 WAIT_ADMIN
        if ("BLOCKING".equals(issue.getIssueHandling())) {
            return IssueNextAction.WAIT_ADMIN;
        }

        // MOVE_LOCATION 케이스
        if ("RESOLVED".equals(issue.getStatus()) && "MOVE_LOCATION".equals(issue.getReasonCode())) {
            return IssueNextAction.MOVE_LOCATION;
        }

        // AUTO_RESOLVED 케이스
        if ("RESOLVED".equals(issue.getStatus())) {
            return IssueNextAction.AUTO_RESOLVED;
        }

        // 기본값: NEXT_ITEM
        return IssueNextAction.NEXT_ITEM;
    }

    /**
     * 관리자 다음 행동 계산 (분기표 AdminNextAction 컬럼 기준)
     */
    private AdminNextAction calculateAdminNextAction(IssueVO issue, AiJudgmentVO judgment) {
        // RETAKE 케이스 - 관리자 개입 없음
        if (judgment != null && "RETAKE".equals(judgment.getAiDecision())) {
            return AdminNextAction.RETAKE_IMAGE;
        }

        // RESOLVED 상태면 관리자 액션 없음
        if ("RESOLVED".equals(issue.getStatus())) {
            return null;
        }

        // BLOCKING 상태는 즉시 통화 필요 (NEED_CHECK, STOCK_EXISTS)
        if ("BLOCKING".equals(issue.getIssueHandling())) {
            return AdminNextAction.ADMIN_JOIN_CALL;
        }

        // adminRequired가 true이면 사후 확정 필요 (NON_BLOCKING + adminRequired=true)
        if (Boolean.TRUE.equals(issue.getAdminRequired())) {
            return AdminNextAction.ADMIN_CONFIRM_LATER;
        }

        return null;
    }

    // ================================================================
    // WebRTC 후처리 (분기표 D2~D4, D6~D7, D10~D11, S2~S3)
    // ================================================================

    /**
     * WebRTC 통화 연결 성공 처리
     * - 분기표 D3, D6, D10, S2 노드
     * - urgency=5 (최하위), adminRequired=false, handling=NON_BLOCKING
     * - 작업자는 즉시 다음 작업 진행, 관리자는 확정 필요
     * 
     * @param issueId Issue ID
     */
    @Transactional
    public void handleWebRtcConnected(Long issueId) {
        log.info("[IssueService] handleWebRtcConnected started. issueId={}", issueId);

        // 1. Issue 조회
        IssueVO issue = issueMapper.findById(issueId);
        if (issue == null) {
            log.warn("[IssueService] Issue not found. issueId={}", issueId);
            throw new ApiException(ErrorCode.ISSUE_NOT_FOUND);
        }

        // 2. 이미 RESOLVED면 처리 안 함
        // NOTE: Issue가 이미 RESOLVED면 WebRTC 결과로 상태를 덮어쓰지 않는다.
        // (MOVE_LOCATION 등 자동 해결 케이스 포함)
        if ("RESOLVED".equals(issue.getStatus())) {
            log.info("[IssueService] Issue already resolved. Skip WebRTC processing. issueId={}", issueId);
            return;
        }

        // 3. WebRTC CONNECTED 정책 적용
        issue.setUrgency(5); // 최하위 우선순위
        issue.setAdminRequired(false); // 관리자 확인 불필요 (통화 완료)
        issue.setIssueHandling("NON_BLOCKING"); // 작업자 즉시 진행 가능

        // 4. Issue 업데이트
        issueMapper.updateIssueStatus(issue);

        // [NEW] WebRTC 연결 성공 -> 아이템 부활 (작업자는 하던거 계속)
        // 관리자가 "이건 안돼"라고 하면 별도 API로 죽여야겠지만,
        // 일단 연결되면 작업자는 "관리자가 보고 있으니 진행해"라는 의미로 CONTINUE 처리
        taskItemService.reviveItem(issue.getBatchTaskItemId());

        log.info("[IssueService] WebRTC CONNECTED processed & Item Revived. issueId={}, urgency=5, adminRequired=false",
                issueId);
    }

    /**
     * WebRTC 통화 연결 실패 처리 (MISSED/REJECTED/TIMEOUT)
     * - 분기표 D4, D7, D11, S3 노드
     * - urgency 조정: NEED_CHECK/STOCK_EXISTS → 1, 나머지 → 2
     * - adminRequired=true 유지, handling=NON_BLOCKING
     * - 작업자는 계속 진행, 관제 큐 유지
     * 
     * @param issueId Issue ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleWebRtcMissed(Long issueId) {
        log.info("[IssueService] handleWebRtcMissed started. issueId={}", issueId);

        // 1. Issue 조회
        IssueVO issue = issueMapper.findById(issueId);
        if (issue == null) {
            log.warn("[IssueService] Issue not found. issueId={}", issueId);
            throw new ApiException(ErrorCode.ISSUE_NOT_FOUND);
        }

        // 2. 이미 RESOLVED면 처리 안 함
        // NOTE: Issue가 이미 RESOLVED면 WebRTC 결과로 상태를 덮어쓰지 않는다.
        if ("RESOLVED".equals(issue.getStatus())) {
            log.info("[IssueService] Issue already resolved. Skip WebRTC processing. issueId={}", issueId);
            return;
        }

        // 3. AI 판정 결과 조회
        AiJudgmentVO judgment = issueMapper.findAiJudgmentByIssueId(issueId);
        String aiDecision = judgment != null ? judgment.getAiDecision() : "UNKNOWN";
        String reasonCode = issue.getReasonCode();

        // 4. WebRTC MISSED 정책 적용
        // NOTE: OUT_OF_STOCK은 reasonCode만 신뢰 (aiDecision은 참고용)
        // NEED_CHECK 또는 STOCK_EXISTS는 최상위 urgency=1
        if ("NEED_CHECK".equals(aiDecision) || "STOCK_EXISTS".equals(reasonCode)) {
            issue.setUrgency(1); // 관제 큐 최상위
            log.info("[IssueService] NEED_CHECK/STOCK_EXISTS → urgency=1. issueId={}", issueId);
        } else {
            // 이미지가 없는 OUT_OF_STOCK 이슈는 사진 업로드 대기 상태로 유지하기 위해 urgency 조정
            // (여기서는 일반적인 2로 설정하되, NextAction 계산 시 이미지 유무 체크)
            issue.setUrgency(2); // 그 외 → urgency=2
            log.info("[IssueService] Other cases → urgency=2. issueId={}", issueId);
        }

        issue.setAdminRequired(true); // 관리자 확인 필요 (연결 실패)
        issue.setIssueHandling("NON_BLOCKING"); // 작업자는 계속 진행 가능
        issue.setWebrtcStatus("NONE"); // [Fix] 통화 상태 초기화 (WAITING에서 탈출)

        // 5. Issue 업데이트
        issueMapper.updateIssueStatus(issue);
        log.info(
                "[IssueService] WebRTC MISSED processed. issueId={}, urgency={}, adminRequired=true, webrtcStatus=NONE",
                issueId, issue.getUrgency());

        // 6. 파손 TEMP 재고 차감 (DAMAGED 타입만)
        if ("DAMAGED".equals(issue.getIssueType())) {
            TaskItemVO item = taskItemService.getTaskItem(issue.getBatchTaskItemId());
            if (item != null) {
                inventoryService.recordEvent(
                        "PICK_DAMAGED_TEMP",
                        item.getProductId(),
                        item.getLocationId(),
                        -1, // 파손 1개 임시 차감
                        "ISSUE",
                        issueId,
                        issue.getWorkerId());
                log.info("[IssueService] PICK_DAMAGED_TEMP event recorded. issueId={}, product={}, location={}",
                        issueId, item.getProductId(), item.getLocationId());
            }
        }

        // 7. [Fix] Task의 진행 상태(action_status)를 다음 아이템 준비 단계(SCAN_LOCATION)로 초기화
        // 이슈 처리가 완료(또는 보류)되었으므로, 해당 아이템에 대한 수량 조절(ADJUST_QUANTITY) 상태를 해제해야 함.
        TaskItemVO taskItemForReset = taskItemService.getTaskItem(issue.getBatchTaskItemId());
        if (taskItemForReset != null) {
            taskMapper.updateActionStatus(taskItemForReset.getBatchTaskId(),
                    lookie.backend.domain.task.vo.TaskActionStatus.SCAN_LOCATION);
            log.info("[IssueService] Task action status reset to SCAN_LOCATION. taskId={}",
                    taskItemForReset.getBatchTaskId());
        }
    }

    // ================================================================
    // 관리자 확정 (분기표 D14, S7)
    // ================================================================

    private static final Set<AdminDecision> DAMAGED_ALLOWED = Set.of(
            AdminDecision.NORMAL, AdminDecision.DAMAGED, AdminDecision.CALLED_OTHER_PROCESS);

    private static final Set<AdminDecision> OUT_OF_STOCK_ALLOWED = Set.of(
            AdminDecision.FIXED);

    /**
     * 관리자 확정 처리
     * - 분기표 D14, S7 노드
     * - status → RESOLVED
     * - adminDecision 저장
     * - resolvedAt 설정
     * - Inventory Event 기록 (DAMAGED만, 현재는 주석 처리)
     * 
     * @param issueId       Issue ID
     * @param adminDecision 관리자 확정 결과 (NORMAL/DAMAGED/CALLED_OTHER_PROCESS/FIXED)
     */
    @Transactional
    public void confirmIssue(Long issueId, AdminDecision adminDecision) {
        log.info("[IssueService] confirmIssue started. issueId={}, adminDecision={}",
                issueId, adminDecision);

        // 1. Issue 조회
        IssueVO issue = issueMapper.findById(issueId);
        if (issue == null) {
            log.warn("[IssueService] Issue not found. issueId={}", issueId);
            throw new ApiException(ErrorCode.ISSUE_NOT_FOUND);
        }

        // 2. 이미 RESOLVED면 중복 확정 방지
        if ("RESOLVED".equals(issue.getStatus())) {
            log.warn("[IssueService] Issue already resolved. issueId={}", issueId);
            throw new ApiException(ErrorCode.ISSUE_ALREADY_RESOLVED);
        }

        // 3. adminDecision 검증
        validateAdminDecision(issue.getIssueType(), adminDecision);

        // 4. 확정 처리
        issue.setStatus("RESOLVED");
        issue.setAdminDecision(adminDecision.name());
        issue.setResolvedAt(java.time.LocalDateTime.now());

        // 5. Issue 업데이트
        issueMapper.updateIssueStatus(issue);
        log.info("[IssueService] Admin confirmed. issueId={}, adminDecision={}",
                issueId, adminDecision);

        // [Fix] NORMAL 판정 시, 작업 진행 중(IN_PROGRESS)이면 아이템 상태 PENDING 복구
        // 작업자가 해당 상품을 다시 집품할 수 있도록 되돌림
        if (AdminDecision.NORMAL.equals(adminDecision)) {
            TaskVO task = taskMapper.findById(issue.getBatchTaskId());
            if (task != null && "IN_PROGRESS".equals(task.getStatus())) {
                taskItemService.reviveItem(issue.getBatchTaskItemId());
                log.info("[IssueService] TaskItem revived to PENDING. issueId={}, itemId={}", issueId,
                        issue.getBatchTaskItemId());
            }
        }

        // 6. Inventory Event 기록 (DAMAGED 타입)
        if ("DAMAGED".equals(issue.getIssueType())) {
            TaskItemVO item = taskItemService.getTaskItem(issue.getBatchTaskItemId());
            if (item != null) {
                if (AdminDecision.DAMAGED.equals(adminDecision)) {
                    // 파손 확정 (마킹용, qty=0)
                    inventoryService.recordEvent(
                            "PICK_DAMAGED_FINAL",
                            item.getProductId(),
                            item.getLocationId(),
                            0, // 추가 차감 없음 (TEMP에서 이미 차감됨)
                            "ISSUE",
                            issueId,
                            null // adminId는 별도 파라미터로 받아야 하지만 생략
                    );
                    log.info("[IssueService] PICK_DAMAGED_FINAL event recorded. issueId={}", issueId);

                } else if (AdminDecision.NORMAL.equals(adminDecision)) {
                    // 파손 취소/복구 (TEMP 복원)
                    inventoryService.recordEvent(
                            "REVERT_DAMAGED",
                            item.getProductId(),
                            item.getLocationId(),
                            +1, // 재고 복구
                            "ISSUE",
                            issueId,
                            null);
                    log.info("[IssueService] REVERT_DAMAGED event recorded. issueId={}", issueId);
                }
            }
        }
    }

    /**
     * adminDecision 유효성 검증
     */
    private void validateAdminDecision(String issueType, AdminDecision adminDecision) {
        if ("DAMAGED".equals(issueType)) {
            // DAMAGED 타입 허용 값 검증
            if (!DAMAGED_ALLOWED.contains(adminDecision)) {
                throw new ApiException(ErrorCode.INVALID_ADMIN_DECISION);
            }
        } else if ("OUT_OF_STOCK".equals(issueType)) {
            // OUT_OF_STOCK 타입 허용 값 검증
            if (!OUT_OF_STOCK_ALLOWED.contains(adminDecision)) {
                throw new ApiException(ErrorCode.INVALID_ADMIN_DECISION);
            }
        } else {
            throw new ApiException(ErrorCode.INVALID_ISSUE_TYPE);
        }
    }

    /**
     * 관리자 관제 이슈 목록 조회
     */
    @Transactional(readOnly = true)
    public AdminIssueListResponse getAdminIssueList(Long adminId, AdminIssueListRequest request) {
        log.info("[IssueService] getAdminIssueList started. adminId={}, status={}, page={}",
                adminId, request.getStatus(), request.getPage());

        List<AdminIssueSummary> issues = issueMapper.findAdminIssues(adminId, request);

        long totalCount = issueMapper.countAdminIssues(adminId, request);

        return AdminIssueListResponse.of(issues, request.getPage(), request.getSize(),
                        totalCount);
    }

    /**
     * 내 이슈 목록 조회
     * - 작업자 본인의 이슈 목록 조회
     * - status 필터링 지원 (OPEN/RESOLVED/null=ALL)
     */
    @Transactional(readOnly = true)
    public List<MyIssueSummary> getMyIssueList(Long workerId, IssueStatus status) {
        log.info("[IssueService] getMyIssueList started. workerId={}, status={}", workerId, status);
        return issueMapper.findMyIssues(workerId, status);
    }

    /**
     * Redis 집계 차감을 위한 Reverted 이벤트 발행 보조 메서드
     */
}
