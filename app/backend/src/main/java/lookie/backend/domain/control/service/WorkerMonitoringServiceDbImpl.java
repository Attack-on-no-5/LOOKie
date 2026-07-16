package lookie.backend.domain.control.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lookie.backend.domain.control.dto.AdminZoneAssignmentRequest;
import lookie.backend.domain.control.dto.DashboardSummaryDto;
import lookie.backend.domain.control.dto.ZoneOverviewDto;
import lookie.backend.domain.control.dto.WorkerHoverDto;
import lookie.backend.domain.control.dto.ZoneWorkerDto;
import lookie.backend.domain.control.dto.AdminResponseDto;
import lookie.backend.domain.control.dto.ZoneSimulationRequest;
import lookie.backend.domain.control.dto.ZoneMoveRequest;
import lookie.backend.domain.control.repository.vo.AdminQueryVo;
import lookie.backend.domain.control.mapper.ControlMapper;
import lookie.backend.domain.batch.mapper.BatchMapper;
import lookie.backend.domain.batch.vo.BatchVO;
import lookie.backend.domain.task.mapper.TaskItemMapper;
import lookie.backend.global.common.type.ZoneType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lookie.backend.global.util.WorkerNameFormatter;
import lookie.backend.domain.user.vo.UserVO;
import java.util.Collections;

/**
 * WorkerMonitoringService의 DB 기반 구현체
 * MyBatis를 사용하여 MySQL DB에서 관제 데이터를 직접 조회합니다.
 * 추후 Redis 기반 구현체로 전환 시 이 클래스는 Deprecated 될 수 있습니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkerMonitoringServiceDbImpl implements WorkerMonitoringService {

    private final ControlMapper controlMapper;
    private final BatchMapper batchMapper;
    private final TaskItemMapper taskItemMapper;
    private final lookie.backend.domain.user.service.UserService userService; // 실시간 상태 주입용

    /**
     * 구역별 현황 조회 구현
     * <p>
     * 1. DB에서 Zone ID 및 통계 데이터를 조회합니다.
     * 2. ZoneType Enum을 사용하여 Zone ID를 사람이 읽을 수 있는 이름('ZONE A' 등)으로 변환합니다.
     */
    @Override
    public List<ZoneOverviewDto> getZoneOverviews() {
        List<ZoneOverviewDto> overviews = controlMapper.selectZoneOverviews();

        // Map zoneName using Enum
        // DB에는 ID만 있으므로, Enum을 통해 'ZONE A' 같은 표시용 이름으로 매핑합니다.
        for (ZoneOverviewDto dto : overviews) {
            dto.setZoneName(ZoneType.getNameById(dto.getZoneId()));
        }

        return overviews;
    }

    /**
     * 작업자 이동 시뮬레이션 기반 구역 요약 조회 (캐시 미사용)
     */
    @Override
    public List<ZoneOverviewDto> simulateZoneOverviews(ZoneSimulationRequest request) {
        List<ZoneMoveRequest> moves = request != null ? request.getMoves() : Collections.emptyList();

        List<ZoneOverviewDto> overviews = (moves == null || moves.isEmpty())
                ? controlMapper.selectZoneOverviews()
                : controlMapper.selectZoneOverviewsSimulated(moves);

        for (ZoneOverviewDto dto : overviews) {
            dto.setZoneName(ZoneType.getNameById(dto.getZoneId()));
        }

        return overviews;
    }

    /**
     * 구역별 작업자 조회 구현
     * 1. 해당 구역의 작업자 리스트를 조회합니다.
     * 2. 개인정보 보호를 위해 이름을 '실명 + 전화번호 뒷 4자리' 형식(예: 홍길동 1234)으로 포맷팅합니다.
     */
    @Override
    public List<ZoneWorkerDto> getWorkersByZone(Long zoneId) {
        List<ZoneWorkerDto> workers = controlMapper.selectWorkersByZoneId(zoneId);

        for (ZoneWorkerDto worker : workers) {
            // Format name: "Name 1234"
            worker.setName(WorkerNameFormatter.format(worker.getName(), worker.getPhoneNumber()));
        }

        return workers;
    }

    /**
     * 대시보드 요약 조회 구현
     * 1. Mapper를 통해 전체 작업자 수, 이슈 통계 등을 각각 카운트합니다.
     * 2. 구역별 현황은 기존 getZoneOverviews() 로직과 동일하게 Enum 매핑을 수행하여 리스트에 포함합니다.
     */
    @Override
    public DashboardSummaryDto getDashboardSummary(Long adminId) {
        // 1. Fetch System Metrics (시스템 전체 지표 조회)
        Integer totalActiveWorkers = controlMapper.countTotalActiveWorkers();
        Integer pendingIssues = controlMapper.countPendingIssuesByAdmin(adminId);
        Integer completedIssues = controlMapper.countTodayCompletedIssuesByAdmin(adminId);

        // 2. Fetch Zone Summaries (Reuse existing logic)
        // 구역별 요약 정보 재사용 (Enum 매핑 로직 포함)
        List<ZoneOverviewDto> zoneSummaries = controlMapper.selectZoneOverviews();
        for (ZoneOverviewDto dto : zoneSummaries) {
            dto.setZoneName(ZoneType.getNameById(dto.getZoneId()));
        }

        // 3. Build DTO (결과 취합)
        double totalProgressRate = 0.0;
        BatchVO currentBatch = batchMapper.findCurrentInProgress();
        if (currentBatch != null) {
            int totalItems = taskItemMapper.countItemsByBatch(currentBatch.getBatchId());
            int completedItems = taskItemMapper.countCompletedItemsByBatch(currentBatch.getBatchId());
            totalProgressRate = totalItems > 0 ? (double) completedItems * 100 / totalItems : 0.0;
            totalProgressRate = Math.round(totalProgressRate * 10.0) / 10.0;
        }

        return DashboardSummaryDto.builder()
                .totalActiveWorkers(totalActiveWorkers != null ? totalActiveWorkers : 0)
                .pendingIssues(pendingIssues != null ? pendingIssues : 0)
                .completedIssues(completedIssues != null ? completedIssues : 0)
                .totalProgressRate(totalProgressRate)
                .zoneSummaries(zoneSummaries)
                .build();
    }

    /**
     * 작업자 호버 정보 조회 구현
     * 1. Mapper 호출하여 기본 데이터 조회
     * 2. 데이터가 없으면 예외 발생 (작업자 없음 or 현재 미활동)
     * 3. 이름 포맷팅 (이름 + 전화번호 뒷 4자리)
     * 4. ZoneType Enum을 이용해 zoneName 매핑
     */
    @Override
    public WorkerHoverDto getWorkerHoverInfo(Long workerId) {
        WorkerHoverDto dto = controlMapper.selectWorkerHoverInfo(workerId);

        // Validation: 데이터가 조회되지 않았으면(null) 예외 처리
        if (dto == null) {
            throw new RuntimeException("해당 작업자가 존재하지 않거나 현재 활동 중이 아닙니다.");
        }

        // Business Logic 1: Name Formatting (Name + Last 4 digits)
        dto.setName(WorkerNameFormatter.format(dto.getName(), dto.getPhoneNumber()));

        // Business Logic 2: Zone Mapping (ZoneId -> ZoneName)
        if (dto.getZoneId() != null) {
            dto.setCurrentZoneName(ZoneType.getNameById(dto.getZoneId()));
        }

        return dto;
    }

    /**
     * 관리자 강제 구역 배정 구현
     * <p>
     * 1. 작업자 및 구역 존재 여부 검증
     * 2. 기존 활성 배정 종료 (active assignment closed)
     * 3. 새로운 배정 이력 로그 생성 (assignment history inserted)
     * 4. Users 테이블의 assigned_zone_id 업데이트 (master table updated)
     * <p>
     * 
     * @Transactional 어노테이션을 통해 원자성 보장
     */
    @Override
    @Transactional
    public void assignWorkerToZone(AdminZoneAssignmentRequest request) {
        Long workerId = request.getWorkerId();
        Long zoneId = request.getZoneId();

        // 1. Validation (작업자 및 구역 존재 확인)
        if (!controlMapper.existsWorker(workerId)) {
            throw new RuntimeException("존재하지 않는 작업자입니다.");
        }
        if (!controlMapper.existsZone(zoneId)) {
            throw new RuntimeException("존재하지 않는 구역입니다.");
        }

        // 2. Close Active Assignment (기존 배정 종료)
        controlMapper.closeActiveAssignment(workerId);

        // 3. Insert Assignment History (새로운 배정 이력 생성)
        // assignment_type='BASE', source='ADMIN'
        controlMapper.insertAssignmentHistory(workerId, zoneId, request.getReason());

        // 4. Update User Master Table (사용자 정보 업데이트)
        controlMapper.updateUserAssignedZone(workerId, zoneId);

    }

    /**
     * 관리자 목록 조회 구현
     * 1. DB 조회 (VO)
     * 2. VO -> DTO 매핑 (순수 데이터와 전송 객체 분리)
     */
    @Override
    public List<AdminResponseDto> getAdmins(Long zoneId, String name) {
        // 1. Fetch raw data as VO
        List<AdminQueryVo> adminVos = controlMapper.selectAdmins(zoneId, name);

        // 2. Fetch UserVO for real-time status injection
        List<UserVO> userVOs = adminVos.stream()
                .map(vo -> {
                    UserVO userVO = new UserVO();
                    userVO.setUserId(vo.getAdminId());
                    return userVO;
                })
                .toList();

        // 3. Inject real-time Redis status (ONLINE/BUSY/PAUSED/AWAY)
        userService.populateUserStatus(userVOs);

        // 4. Map VO to DTO with real-time status
        return java.util.stream.IntStream.range(0, adminVos.size())
                .mapToObj(i -> {
                    AdminQueryVo vo = adminVos.get(i);
                    UserVO userVO = userVOs.get(i);

                    return AdminResponseDto.builder()
                            .adminId(vo.getAdminId())
                            .name(vo.getRawName()) // 정책: 관리자는 원본 이름 사용
                            .assignedZoneId(vo.getAssignedZoneId())
                            .zoneName(ZoneType.getNameById(vo.getAssignedZoneId())) // Enum 매핑
                            .currentStatus(userVO.getStatus()) // 실시간 Redis 상태 사용
                            .build();
                })
                .toList();
    }
}
