package lookie.backend.domain.webrtc.service;

import io.livekit.server.AccessToken;
import io.livekit.server.RoomJoin;
import io.livekit.server.Room;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lookie.backend.domain.webrtc.dto.WebRtcDto;
import lookie.backend.domain.webrtc.mapper.CallHistoryMapper;
import lookie.backend.domain.webrtc.vo.CallHistoryVO;
import lookie.backend.global.config.LiveKitConfig;
import lookie.backend.global.error.ApiException;
import lookie.backend.global.error.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import lookie.backend.domain.webrtc.event.CallEndedEvent;
import lookie.backend.domain.webrtc.event.CallRejectedEvent;
import lookie.backend.domain.webrtc.dto.WebRtcSignalType;
import lookie.backend.domain.webrtc.dto.WebRtcSignalResponse;
import lookie.backend.domain.user.mapper.UserMapper;
import lookie.backend.domain.user.vo.UserVO;
import lookie.backend.global.constant.RedisKeyConstants;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Collections;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "livekit.enabled", havingValue = "true", matchIfMissing = true)
public class LiveKitService {

    // ... (fields remain the same) ...
    private final LiveKitConfig liveKitConfig;
    private final StringRedisTemplate redisTemplate;
    private final CallHistoryMapper callHistoryMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final io.livekit.server.RoomServiceClient roomServiceClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final UserMapper userMapper;
    private final lookie.backend.domain.issue.service.IssueServiceNew issueService;

    private static final String STATUS_BUSY = "BUSY";

    /**
     * [Helper] 트랜잭션 커밋 후 실행 (WebSocket 시그널 분리)
     */
    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            // 트랜잭션이 없으면 바로 실행
            action.run();
        }
    }

    // ... (previous methods) ...

    /**
     * 1. 화상 요청 (전화 걸기)
     */
    @Transactional
    public WebRtcDto.CallResponse makeCall(WebRtcDto.CallRequest request) {
        // [New] 이슈 관련 연결일 경우, Task 상태 검증 (완료된 작업이면 화상 연결 불가)
        if (request.getIssueId() != null) {
            issueService.validateIssueForCall(request.getIssueId());
        }

        // [NEW] calleeId 자동 선택 로직
        Long calleeId = request.getCalleeId();
        if (calleeId == null) {
            try {
                calleeId = selectAvailableManagerWithRetry(request.getCallerId());
                log.info("[자동선택] Worker {} → Manager {} 자동 배정 완료", request.getCallerId(), calleeId);
            } catch (ApiException e) {
                // 관리자 부재 시에도 이슈 상태 변경 (NON_BLOCKING) -> 작업자 진행 가능
                if (request.getIssueId() != null) {
                    try {
                        issueService.onWebrtcMissed(request.getIssueId());
                        log.info("🚫 [makeCall] 관리자 부재 -> 이슈 상태 NON_BLOCKING 전환. issueId={}", request.getIssueId());
                    } catch (Exception ex) {
                        log.error("❌ [makeCall] 이슈 상태 변경 실패", ex);
                    }
                }
                throw e;
            }
        } else {
            // 명시적 지정도 원자 예약(SETNX)으로 1:1 보장
            if (!reserveManager(calleeId)) {
                // 예약 실패 사유(BUSY/AWAY/PAUSED)를 구체적 에러로 변환 (상태 없으면 generic BUSY)
                validateUserAvailable(calleeId);
                throw new ApiException(ErrorCode.WEBRTC_MANAGER_BUSY);
            }
        }

        // Lambda에서 사용하기 위한 final 변수
        final Long resolvedCalleeId = calleeId;

        // 관리자 예약은 selectAvailableManager/reserveManager 에서 원자적으로 확정됨
        // (기존 validateUserAvailable read-then-check 는 setUserBusy write 와 비원자 → 중복 배정 원인, 제거)
        String roomName = generateRoomName(request.getCallerId(), resolvedCalleeId);

        CallHistoryVO call = CallHistoryVO.builder()
                .roomName(roomName)
                .callerId(request.getCallerId())
                .calleeId(resolvedCalleeId) // 자동 선택된 ID 사용
                .issueId(request.getIssueId())
                .status("WAITING")
                .createdAt(LocalDateTime.now())
                .build();

        callHistoryMapper.save(call);
        // 관리자 예약은 위 selection 단계에서 SETNX로 원자 확정됨 (setUserBusy 비원자 set 제거)
        String token = generateToken(request.getCallerId().toString(), roomName);

        // [Refactored] Single Payload Generation
        WebRtcSignalResponse payload = WebRtcSignalResponse.from(WebRtcSignalType.REQUESTED, call.getId(), null,
                request.getCallerId(), request.getIssueId());

        runAfterCommit(() -> sendDualPathSignal(resolvedCalleeId, payload));

        return new WebRtcDto.CallResponse(call.getId(), roomName, token);
    }

    /**
     * 2. 통화 상태 조회
     */
    @Transactional(readOnly = true)
    public CallHistoryVO getCallStatus(Long callId) {
        return callHistoryMapper.findById(callId)
                .orElseThrow(() -> new ApiException(ErrorCode.WEBRTC_SESSION_NOT_FOUND));
    }

    /**
     * 3. 화상 수락 (전화 받기)
     */
    @Transactional
    public String acceptCall(Long callId) {
        CallHistoryVO call = callHistoryMapper.findById(callId)
                .orElseThrow(() -> new ApiException(ErrorCode.WEBRTC_SESSION_NOT_FOUND));

        if (!"WAITING".equals(call.getStatus())) {
            throw new ApiException(ErrorCode.WEBRTC_CLIENT_ERROR, "대기 중인 통화가 아닙니다.");
        }

        call.setStatus("ACTIVE");
        call.setStartTime(LocalDateTime.now());
        callHistoryMapper.update(call);

        String token = generateToken(call.getCalleeId().toString(), call.getRoomName());

        // [Refactored] Single Payload Generation
        WebRtcSignalResponse payload = WebRtcSignalResponse.from(WebRtcSignalType.ACCEPTED, call.getId(),
                call.getRoomName(), call.getCalleeId(), call.getIssueId());

        runAfterCommit(() -> sendSignal(payload));

        return token;
    }

    /**
     * 4. 화상 거절
     */
    @Transactional
    public void rejectCall(Long callId) {
        CallHistoryVO call = callHistoryMapper.findById(callId)
                .orElseThrow(() -> new ApiException(ErrorCode.WEBRTC_SESSION_NOT_FOUND));

        call.setStatus("REJECTED");
        callHistoryMapper.update(call);

        if (call.getIssueId() != null) {
            eventPublisher.publishEvent(new CallRejectedEvent(
                    call.getId(),
                    call.getIssueId(),
                    call.getCallerId(),
                    call.getCalleeId(),
                    "REJECTED"));
        }

        clearUserStatus(call.getCalleeId());
        closeLiveKitRoom(call.getRoomName());

        // [Refactored] Single Payload Generation (Shared UUID)
        WebRtcSignalResponse payload = WebRtcSignalResponse.from(WebRtcSignalType.REJECTED, call.getId(),
                call.getRoomName(), call.getCalleeId(), call.getIssueId());

        runAfterCommit(() -> {
            // 1. Waiting 중인 Caller가 보고 있는 Call Topic
            sendSignal(payload);

            // 2. 확실한 전달을 위한 User Queue (Dual-Path)
            sendDualPathSignal(call.getCallerId(), payload);
        });
    }

    /**
     * 5. 화상 종료
     */
    @Transactional
    public void endCall(Long callId) {
        CallHistoryVO call = callHistoryMapper.findById(callId)
                .orElseThrow(() -> new ApiException(ErrorCode.WEBRTC_SESSION_NOT_FOUND));

        call.setStatus("ENDED");
        call.setEndTime(LocalDateTime.now());
        callHistoryMapper.update(call);

        if (call.getIssueId() != null) {
            eventPublisher.publishEvent(new CallEndedEvent(
                    call.getId(), call.getIssueId(), call.getCallerId(), call.getCalleeId()));
        }

        Long senderId = getCurrentUserId();

        try {
            clearUserStatus(call.getCalleeId());
            closeLiveKitRoom(call.getRoomName());
        } catch (Exception e) {
            log.error("[endCall] 리소스 정리 중 오류 발생: {}", e.getMessage());
        } finally {
            // [Refactored] Single Payload Generation (Shared UUID)
            WebRtcSignalResponse payload = WebRtcSignalResponse.from(WebRtcSignalType.ENDED, call.getId(), null,
                    senderId, call.getIssueId());

            runAfterCommit(() -> {
                // To Call Topic
                sendSignal(payload);

                // To User Queue (Dual-Path)
                Long targetId = call.getCallerId().equals(senderId) ? call.getCalleeId() : call.getCallerId();
                sendDualPathSignal(targetId, payload);
            });
        }
    }

    /**
     * 6. 화상 요청 취소 및 미응답 처리
     */
    @Transactional
    public void cancelCall(Long callId, WebRtcDto.CancelRequest request) {
        CallHistoryVO call = callHistoryMapper.findById(callId)
                .orElseThrow(() -> new ApiException(ErrorCode.WEBRTC_SESSION_NOT_FOUND));

        if (!"WAITING".equals(call.getStatus())) {
            throw new ApiException(ErrorCode.WEBRTC_CLIENT_ERROR, "대기 중인 통화만 취소할 수 있습니다.");
        }

        if ("TIMEOUT".equals(request.getReason())) {
            call.setStatus("NO_ANSWER");
            if (call.getIssueId() != null) {
                eventPublisher.publishEvent(new CallRejectedEvent(
                        call.getId(), call.getIssueId(), call.getCallerId(), call.getCalleeId(), "TIMEOUT"));
            }
        } else {
            call.setStatus("CANCELED");
        }

        callHistoryMapper.update(call);

        clearUserStatus(call.getCalleeId());
        closeLiveKitRoom(call.getRoomName());

        // [Refactored] Single Payload Generation
        WebRtcSignalResponse payload = WebRtcSignalResponse.from(WebRtcSignalType.CANCELED, call.getId(), null,
                call.getCallerId(), call.getIssueId());

        runAfterCommit(() -> sendDualPathSignal(call.getCalleeId(), payload));
    }

    // --- 내부 메서드 ---

    /**
     * 가용한 관리자 자동 선택 (재시도 로직 포함)
     * 
     * @param workerId 작업자 ID
     * @return 선택된 관리자 ID
     * @throws ApiException 가용한 관리자가 없을 경우
     */
    private Long selectAvailableManagerWithRetry(Long workerId) {
        // 동시성 이슈 방지를 위한 재시도 로직 (3회)
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                // selectAvailableManager 가 원자 예약(SETNX)까지 확정 → 별도 재검증 불필요
                return selectAvailableManager(workerId);
            } catch (ApiException e) {
                if (attempt == 2) {
                    // 마지막 시도 실패 시 예외 발생
                    throw e;
                }
                // 100ms 대기 후 재시도
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(ErrorCode.WEBRTC_MANAGER_BUSY, "관리자 선택 중 인터럽트 발생");
                }
                log.warn("[재시도] 관리자 선택 실패, {}회 재시도 중... (Worker: {})", attempt + 1, workerId);
            }
        }
        throw new ApiException(ErrorCode.WEBRTC_MANAGER_BUSY, "관리자 선택 실패");
    }

    /**
     * 가용한 관리자 자동 선택 (1:1 제약 준수)
     * 
     * @param workerId 작업자 ID
     * @return 선택된 관리자 ID
     * @throws ApiException 가용한 관리자가 없을 경우
     */
    private Long selectAvailableManager(Long workerId) {
        // 1. Worker의 구역 조회
        UserVO worker = userMapper.findById(workerId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        Long zoneId = worker.getAssignedZoneId();
        if (zoneId == null) {
            throw new ApiException(ErrorCode.WORKER_ZONE_NOT_ASSIGNED,
                    "작업자에게 배정된 구역이 없습니다.");
        }

        // 2. 해당 구역의 관리자 목록 조회
        List<UserVO> managers = userMapper.findAdminsByZone(zoneId);
        if (managers.isEmpty()) {
            throw new ApiException(ErrorCode.WEBRTC_MANAGER_BUSY,
                    "해당 구역에 관리자가 배정되지 않았습니다.");
        }

        // 3. Redis에서 가용 상태 필터링
        List<UserVO> availableManagers = managers.stream()
                .filter(manager -> {
                    // 1) 자기 자신 제외 (루프 방지)
                    if (manager.getUserId().equals(workerId))
                        return false;

                    // 2) Redis 상태 확인 (가용성)
                    String key = RedisKeyConstants.USER_STATUS_KEY + manager.getUserId();
                    String status = redisTemplate.opsForValue().get(key);
                    return status == null; // BUSY/AWAY/PAUSED가 아닌 경우만
                })
                .collect(Collectors.toList());

        if (availableManagers.isEmpty()) {
            throw new ApiException(ErrorCode.WEBRTC_MANAGER_BUSY,
                    "현재 가용한 관리자가 없습니다. 잠시 후 다시 시도해주세요.");
        }

        // 4. Random 순서로 후보를 돌며 원자 예약(SETNX) 성공한 첫 관리자를 확정 (1:1 제약)
        Collections.shuffle(availableManagers);
        for (UserVO manager : availableManagers) {
            Long candidateId = manager.getUserId();
            if (reserveManager(candidateId)) {
                log.info("[자동선택] Worker {} → Manager {} 예약 성공 (Zone: {}, 가용후보: {}/{})",
                        workerId, candidateId, zoneId, availableManagers.size(), managers.size());
                return candidateId;
            }
        }

        // 모든 후보가 동시 예약 경합에서 밀림 → 재시도 루프가 처리
        throw new ApiException(ErrorCode.WEBRTC_MANAGER_BUSY,
                "현재 가용한 관리자가 없습니다. 잠시 후 다시 시도해주세요.");
    }

    /**
     * 관리자 원자 예약 (SETNX + TTL).
     * setIfAbsent 로 키가 없을 때만 BUSY 로 선점 → 동시 요청 중 정확히 1건만 성공한다.
     * 기존 setUserBusy(비원자 set)의 check-then-act 경합(동일 관리자 중복 배정)을 제거하는 핵심.
     */
    private boolean reserveManager(Long userId) {
        String key = RedisKeyConstants.USER_STATUS_KEY + userId;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, STATUS_BUSY, 10, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * LiveKit 토큰 생성
     */
    private String generateToken(String identity, String roomName) {
        try {
            AccessToken token = new AccessToken(liveKitConfig.getApiKey(), liveKitConfig.getApiSecret());
            token.setName(identity);
            token.setIdentity(identity);

            // [Security Fix] Token Restrictions
            token.addGrants(new RoomJoin(true), new Room(roomName));

            return token.toJwt();
        } catch (Exception e) {
            log.error("[LiveKit] 토큰 생성 실패: {}", e.getMessage(), e);
            throw new ApiException(ErrorCode.WEBRTC_SERVER_ERROR, "LiveKit 토큰 생성에 실패했습니다.");
        }
    }

    /**
     * Room 이름 생성 (고유성 보장)
     */
    private String generateRoomName(Long callerId, Long calleeId) {
        return String.format("call-%d-%d-%d", callerId, calleeId, System.currentTimeMillis());
    }

    // --- Redis 메서드 ---

    private void validateUserAvailable(Long userId) {
        String key = RedisKeyConstants.USER_STATUS_KEY + userId;
        String status = redisTemplate.opsForValue().get(key);

        if (status != null) {
            switch (status) {
                case "BUSY":
                    throw new ApiException(ErrorCode.WEBRTC_MANAGER_BUSY);
                case "AWAY":
                    throw new ApiException(ErrorCode.WEBRTC_USER_AWAY);
                case "PAUSED":
                    throw new ApiException(ErrorCode.WEBRTC_USER_PAUSED);
                default:
                    throw new ApiException(ErrorCode.WEBRTC_MANAGER_BUSY);
            }
        }
    }

    private void clearUserStatus(Long userId) {
        String key = RedisKeyConstants.USER_STATUS_KEY + userId;
        redisTemplate.delete(key);
    }

    /**
     * LiveKit Room 삭제 (리소스 정리)
     */
    private void closeLiveKitRoom(String roomName) {
        try {
            // Room 삭제 API 호출
            roomServiceClient.deleteRoom(roomName);
            log.info("[LiveKit] Room 삭제 성공: {}", roomName);
        } catch (Exception e) {
            log.error("[LiveKit] Room 삭제 실패: {}", e.getMessage());
            // Room이 이미 없거나 오류가 발생해도 비즈니스 로직은 계속 진행
        }
    }

    /**
     * WebSocket 시그널 전송 공통 메서드 (Legacy Wrapper)
     */
    private void sendSignal(Long callId, WebRtcSignalType type, String roomName, Long senderId) {
        sendSignal(WebRtcSignalResponse.from(type, callId, roomName, senderId, null));
    }

    /**
     * [Overloaded] WebSocket 시그널 전송 (Direct Payload)
     */
    private void sendSignal(WebRtcSignalResponse payload) {
        messagingTemplate.convertAndSend("/topic/video-calls/" + payload.getCallId(), payload);
        log.info("[WebSocket] Signal sent: callId={}, type={}, senderId={}, msgId={}",
                payload.getCallId(), payload.getType(), payload.getSenderId(), payload.getMessageId());
    }

    /**
     * [Refactored] 사용자에게 확실하게 알림 전송 (Dual-Path Strategy) (Legacy Wrapper)
     */
    private void sendDualPathSignal(Long userId, WebRtcSignalType type, Long callId, String roomName, Long senderId) {
        sendDualPathSignal(userId, WebRtcSignalResponse.from(type, callId, roomName, senderId, null));
    }

    /**
     * [Overloaded] 사용자에게 확실하게 알림 전송 (Direct Payload)
     */
    private void sendDualPathSignal(Long userId, WebRtcSignalResponse payload) {
        if (userId == null)
            return;
        sendSignalToUser(userId, payload);
        sendSignalToUserTopic(userId, payload);
    }

    /**
     * 특정 사용자에게 1:1 시그널 전송
     * Destination: /user/{userId}/queue/calls
     */
    private void sendSignalToUser(Long userId, WebRtcSignalResponse payload) {
        messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/calls", payload);
        log.info("[WebSocket] Private Signal sent to User {}: callId={}, type={}, msgId={}",
                userId, payload.getCallId(), payload.getType(), payload.getMessageId());
    }

    /**
     * 특정 사용자에게 Topic으로 시그널 전송 (Fallback)
     * Destination: /topic/calls/{userId}
     */
    private void sendSignalToUserTopic(Long userId, WebRtcSignalResponse payload) {
        messagingTemplate.convertAndSend("/topic/calls/" + userId, payload);
        log.info("[WebSocket] Public Topic Signal sent to User {}: callId={}, type={}, msgId={}",
                userId, payload.getCallId(), payload.getType(), payload.getMessageId());
    }

    /**
     * SecurityContext에서 현재 사용자 ID 추출
     */
    private Long getCurrentUserId() {
        try {
            String userId = SecurityContextHolder.getContext().getAuthentication().getName();
            return Long.parseLong(userId);
        } catch (Exception e) {
            log.warn("[getCurrentUserId] 사용자 ID 추출 실패: {}", e.getMessage());
            return null;
        }
    }
}
