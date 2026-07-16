package lookie.backend.domain.control.service;

import java.util.List;

import lookie.backend.domain.control.dto.AdminZoneAssignmentRequest;
import lookie.backend.domain.control.dto.DashboardSummaryDto;
import lookie.backend.domain.control.dto.ZoneOverviewDto;
import lookie.backend.domain.control.dto.WorkerHoverDto;
import lookie.backend.domain.control.dto.ZoneSimulationRequest;

import lookie.backend.domain.control.dto.ZoneWorkerDto;
import lookie.backend.domain.control.dto.AdminResponseDto;

/**
 * 현장 관제(Control Map) 모니터링을 위한 서비스 인터페이스
 * 구역별 현황, 작업자 모니터링, 대시보드 요약 정보를 제공
 */
public interface WorkerMonitoringService {

    /**
     * 모든 구역(Zone)의 작업자 수와 진행률 현황을 조회합니다.
     *
     * @return 구역별 요약 정보 리스트
     */
    List<ZoneOverviewDto> getZoneOverviews();

    /**
     * 특정 구역에 배치된 작업자들의 상세 정보를 조회합니다.
     *
     * @param zoneId 조회할 구역 ID
     * @return 해당 구역의 작업자 리스트
     */
    List<ZoneWorkerDto> getWorkersByZone(Long zoneId);

    /**
     * 전체 관제 대시보드를 위한 시스템 요약 통계를 조회합니다.
     *
     * @return 대시보드 요약 정보 (전체 작업자 수, 이슈 현황 등)
     */

    DashboardSummaryDto getDashboardSummary(Long adminId);

    /**
     * 작업자 마우스 오버 시 정보를 조회합니다.
     *
     * @param workerId 작업자 ID
     * @return 작업자 호버 정보 DTO
     */
    WorkerHoverDto getWorkerHoverInfo(Long workerId);

    /**
     * 관리자 강제 구역 배정
     *
     * @param request 배정 요청 정보 (작업자 ID, 구역 ID, 사유)
     */
    void assignWorkerToZone(AdminZoneAssignmentRequest request);

    /**
     * 관리자 목록 조회 (검색 및 필터링)
     *
     * @param zoneId 구역 필터링 (선택)
     * @param name   이름 검색 (선택)
     * @return 관리자 목록 DTO
     */
    List<AdminResponseDto> getAdmins(Long zoneId, String name);

    /**
     * ?묒뾽???댁쟾 ?곌낵???곗씠??(諛곗튂 泥섎━ ?꾨룞) 援ъ뿭 ???곹깭???꾪솴
     *
     * @param request 이동 예정 작업자 목록
     * @return 시뮬레이션된 구역 요약 정보
     */
    List<ZoneOverviewDto> simulateZoneOverviews(ZoneSimulationRequest request);
}
