package lookie.backend.domain.control.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lookie.backend.domain.control.dto.*;
import lookie.backend.domain.control.mapper.ControlMapper;
import lookie.backend.domain.control.mapper.RebalanceMapper;
import lookie.backend.domain.control.repository.ControlRedisRepository;
import lookie.backend.domain.control.repository.vo.AdminQueryVo;
import lookie.backend.domain.user.service.UserService;
import lookie.backend.domain.user.vo.UserVO;
import lookie.backend.global.common.type.ZoneType;
import lookie.backend.global.util.WorkerNameFormatter;
import lookie.backend.domain.batch.mapper.BatchMapper;
import lookie.backend.domain.batch.vo.BatchVO;
import lookie.backend.domain.task.mapper.TaskItemMapper;
import lookie.backend.domain.control.vo.RebalanceSnapshotVO;
import lookie.backend.infra.ai.AiRebalanceClient;
import lookie.backend.infra.ai.dto.RebalanceRecommendResponse;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.HashMap;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * WorkerMonitoringService??Redis 湲곕컲 援ы쁽泥?
 * - Cache-Aside ?⑦꽩 ?곸슜: Redis 議고쉶 ??Cache Miss ??DB Fallback ??Redis 罹먯떛
 * - @Primary ?대끂?뚯씠?섏쑝濡?湲곕낯 援ы쁽泥??ㅼ젙
 * - 湲곗〈 DB 援ы쁽泥?WorkerMonitoringServiceDbImpl)???좎? (Fallback 諛?鍮꾧탳??
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkerMonitoringServiceRedisImpl implements WorkerMonitoringService {

    private final ControlRedisRepository redisRepository;
    private final ControlMapper controlMapper; // DB Fallback??
    private final UserService userService; // ?ㅼ떆媛??곹깭 二쇱엯??
    private final BatchMapper batchMapper;
    private final RebalanceMapper rebalanceMapper;
    private final TaskItemMapper taskItemMapper;
    private final AiRebalanceClient aiRebalanceClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String AI_ZONE_RISK_KEY = "lookie:control:ai:zone_risk";
    // :progress 키 TTL — 상위 배치 종료 후 자동 정리 (설계 의도: 종료 24시간 후 만료)
    private static final long PROGRESS_KEY_TTL_HOURS = 24;

    // 구역 상태(NORMAL/STABLE) 판정 임계값(분). 하드코딩 30을 외부 설정으로 분리.
    // 이 값이 SQL 사전계산 status를 덮어쓰는 최종 판정값(getZoneOverviews 응답 경로).
    @org.springframework.beans.factory.annotation.Value("${control.zone-status-threshold-minutes:30}")
    private int zoneStatusThresholdMinutes;
    private static final double RISK_CRITICAL_MIN = 20000.0;
    private static final double RISK_STABLE_MAX = 1000.0;

    /**
     * 援ъ뿭蹂??꾪솴 議고쉶 (Cache-Aside ?⑦꽩)
     * 1. Redis 議고쉶 ?쒕룄
     * 2. Cache Hit ??利됱떆 諛섑솚
     * 3. Cache Miss ??DB 議고쉶 ??Redis 罹먯떛 ??諛섑솚
     */
    @Override
    public List<ZoneOverviewDto> getZoneOverviews() {
        // [Lazy Init] Redis 珥덇린??蹂댁옣
        ensureRedisInitialized();

        log.debug("[Redis Service] Zone Overviews 議고쉶 ?쒖옉");

        // 1. Redis 議고쉶 ?쒕룄
        List<ZoneOverviewDto> cached = redisRepository.getAllZoneOverviews();

        if (cached != null && !cached.isEmpty()) {
            applyStatusFromEta(cached);
            log.info("[Redis Service] Zone Overviews 罹먯떆 ?덊듃: count={}", cached.size());
            return cached;
        }

        // 2. Cache Miss ??DB 議고쉶
        log.info("[Redis Service] Zone Overviews 罹먯떆 誘몄뒪 ??DB 議고쉶");
        List<ZoneOverviewDto> fromDb = controlMapper.selectZoneOverviews();

        // 3. Enum 留ㅽ븨 (湲곗〈 濡쒖쭅 ?좎?)
        for (ZoneOverviewDto dto : fromDb) {
            dto.setZoneName(ZoneType.getNameById(dto.getZoneId()));
        }

        applyStatusFromEta(fromDb);

        // 4. Redis??罹먯떛 (Cache Warm-up)
        for (ZoneOverviewDto dto : fromDb) {
            try {
                redisRepository.saveZoneOverview(dto.getZoneId(), dto);
            } catch (Exception e) {
                log.error("[Redis Service] Zone Overview 罹먯떛 ?ㅽ뙣: zoneId={}, error={}",
                        dto.getZoneId(), e.getMessage());
            }
        }

        log.info("[Redis Service] Zone Overviews DB 議고쉶 諛?罹먯떛 ?꾨즺: count={}", fromDb.size());
        return fromDb;
    }

    /**
     * 援ъ뿭蹂??묒뾽??議고쉶 (Cache-Aside ?⑦꽩)
     */
    @Override
    public List<ZoneWorkerDto> getWorkersByZone(Long zoneId) {
        log.debug("[Redis Service] Zone Workers 議고쉶 ?쒖옉: zoneId={}", zoneId);

        // 1. Redis 議고쉶 ?쒕룄
        List<ZoneWorkerDto> cached = redisRepository.getWorkersByZone(zoneId);

        if (cached != null && !cached.isEmpty()) {
            log.info("[Redis Service] Zone Workers 罹먯떆 ?덊듃: zoneId={}, count={}", zoneId, cached.size());
            return cached;
        }

        // 2. Cache Miss ??DB 議고쉶
        log.info("[Redis Service] Zone Workers 罹먯떆 誘몄뒪 ??DB 議고쉶: zoneId={}", zoneId);
        List<ZoneWorkerDto> fromDb = controlMapper.selectWorkersByZoneId(zoneId);

        // 3. ?대쫫 ?щ㎎??諛?Redis 罹먯떛
        for (ZoneWorkerDto worker : fromDb) {
            worker.setName(WorkerNameFormatter.format(worker.getName(), worker.getPhoneNumber()));

            try {
                redisRepository.saveWorkerStatus(worker.getWorkerId(), worker);
            } catch (Exception e) {
                log.error("[Redis Service] Worker Status 罹먯떛 ?ㅽ뙣: workerId={}, error={}",
                        worker.getWorkerId(), e.getMessage());
            }
        }

        log.info("[Redis Service] Zone Workers DB 議고쉶 諛?罹먯떛 ?꾨즺: zoneId={}, count={}",
                zoneId, fromDb.size());
        return fromDb;
    }

    /**
     * 援ъ뿭蹂??꾪솴 ?쒕??덉씠??議고쉶 (?묒뾽???대룞 誘몃━蹂닿린)
     * - moves媛 ?놁쓣 ?뚮쭔 AI risk ?곹깭瑜??곸슜 (AI???대룞 ?쒕??덉씠???낅젰??諛쏆? ?딆쓬)
     */
    @Override
    public List<ZoneOverviewDto> simulateZoneOverviews(ZoneSimulationRequest request) {
        List<ZoneMoveRequest> moves = request != null ? request.getMoves() : Collections.emptyList();

        List<ZoneOverviewDto> fromDb = (moves == null || moves.isEmpty())
                ? controlMapper.selectZoneOverviews()
                : controlMapper.selectZoneOverviewsSimulated(moves);

        for (ZoneOverviewDto dto : fromDb) {
            dto.setZoneName(ZoneType.getNameById(dto.getZoneId()));
        }

        // Keep status source consistent with visible ETA/deadline values.
        applyStatusFromEta(fromDb);

        return fromDb;
    }

    /**
     * ??쒕낫???붿빟 議고쉶 (Cache-Aside ?⑦꽩)
     */
    @Override
    public DashboardSummaryDto getDashboardSummary(Long adminId) {
        ensureRedisInitialized();

        Integer totalActiveWorkers = controlMapper.countTotalActiveWorkers();
        Integer pendingIssues = controlMapper.countPendingIssuesByAdmin(adminId);
        Integer completedIssues = controlMapper.countTodayCompletedIssuesByAdmin(adminId);
        List<ZoneOverviewDto> zoneSummaries = getZoneOverviews();

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

    private void applyAiRiskStatus(List<ZoneOverviewDto> zones) {
        if (zones == null || zones.isEmpty()) {
            return;
        }

        Map<Long, Double> riskMap = loadAiRiskMap();
        applyAiRiskStatusFromMap(zones, riskMap);
    }

    private void applyAiRiskStatusFromMap(List<ZoneOverviewDto> zones, Map<Long, Double> riskMap) {
        if (zones == null || zones.isEmpty()) {
            return;
        }
        if (riskMap == null || riskMap.isEmpty()) {
            return;
        }

        List<Map.Entry<Long, Double>> positives = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : riskMap.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                positives.add(entry);
            }
        }

        positives.sort(Comparator.comparingDouble((Map.Entry<Long, Double> e) -> e.getValue()).reversed());
        Map<Long, Integer> tileMap = new HashMap<>();
        int n = positives.size();
        for (int i = 0; i < n; i++) {
            int tile = (i * 3) / n; // 0..2
            tileMap.put(positives.get(i).getKey(), tile);
        }

        for (ZoneOverviewDto dto : zones) {
            if (dto.getZoneId() == null) {
                continue;
            }
            Double risk = riskMap.get(dto.getZoneId());
            if (risk == null) {
                continue;
            }
            if (risk <= 0) {
                dto.setStatus("STABLE");
                continue;
            }
            if (risk >= RISK_CRITICAL_MIN) {
                dto.setStatus("CRITICAL");
                continue;
            }
            if (risk <= RISK_STABLE_MAX) {
                dto.setStatus("STABLE");
                continue;
            }
            Integer tile = tileMap.get(dto.getZoneId());
            if (tile == null || tile == 0) {
                dto.setStatus("CRITICAL");
            } else if (tile == 1) {
                dto.setStatus("NORMAL");
            } else {
                dto.setStatus("STABLE");
            }
        }
    }

    private Map<Long, Double> toRiskMap(RebalanceRecommendResponse response) {
        if (response == null || response.getZoneRisks() == null) {
            return Collections.emptyMap();
        }
        Map<Long, Double> map = new HashMap<>();
        for (RebalanceRecommendResponse.ZoneRiskInfo info : response.getZoneRisks()) {
            if (info.getZoneId() == null) {
                continue;
            }
            Double risk = info.getRiskAfter() != null ? info.getRiskAfter() : info.getRiskBefore();
            if (risk != null) {
                map.put(info.getZoneId(), risk);
            }
        }
        return map;
    }

    private void applyStatusFromEta(List<ZoneOverviewDto> zones) {
        if (zones == null || zones.isEmpty()) {
            return;
        }

        for (ZoneOverviewDto dto : zones) {
            Double deadline = dto.getRemainingDeadlineMinutes();
            Double eta = dto.getEstimatedCompletionMinutes();
            if (deadline == null || eta == null) {
                continue;
            }
            double gap = deadline - eta;
            if (gap < 0) {
                dto.setStatus("CRITICAL");
            } else if (gap <= zoneStatusThresholdMinutes) {
                dto.setStatus("NORMAL");
            } else {
                dto.setStatus("STABLE");
            }
        }
    }

    private Map<Long, Double> loadAiRiskMap() {
        try {
            String json = stringRedisTemplate.opsForValue().get(AI_ZONE_RISK_KEY);
            if (json == null || json.isEmpty()) {
                return Collections.emptyMap();
            }

            Map<String, Double> raw = objectMapper.readValue(
                    json, new TypeReference<Map<String, Double>>() {});
            Map<Long, Double> mapped = new HashMap<>();
            for (Map.Entry<String, Double> entry : raw.entrySet()) {
                try {
                    mapped.put(Long.parseLong(entry.getKey()), entry.getValue());
                } catch (NumberFormatException ignored) {
                    // skip invalid key
                }
            }
            return mapped;
        } catch (Exception e) {
            log.warn("[Redis Service] Failed to load AI zone risks: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * ?묒뾽???몃쾭 ?뺣낫 議고쉶
     * - ?몃쾭 ?뺣낫???ㅼ떆媛꾩꽦??留ㅼ슦 以묒슂?섎?濡?罹먯떛?섏? ?딆쓬
     * - ??긽 DB?먯꽌 理쒖떊 ?곗씠??議고쉶
     */
    @Override
    public WorkerHoverDto getWorkerHoverInfo(Long workerId) {
        log.debug("[Redis Service] Worker Hover Info 議고쉶: workerId={} (罹먯떛 ????", workerId);

        WorkerHoverDto dto = controlMapper.selectWorkerHoverInfo(workerId);

        if (dto == null) {
            throw new RuntimeException("?대떦 ?묒뾽?먭? 議댁옱?섏? ?딄굅???꾩옱 ?쒕룞 以묒씠 ?꾨떃?덈떎.");
        }

        // ?대쫫 ?щ㎎??
        dto.setName(WorkerNameFormatter.format(dto.getName(), dto.getPhoneNumber()));

        // Zone 留ㅽ븨
        if (dto.getZoneId() != null) {
            dto.setCurrentZoneName(ZoneType.getNameById(dto.getZoneId()));
        }

        return dto;
    }

    /**
     * 愿由ъ옄 媛뺤젣 援ъ뿭 諛곗젙
     * - DB ?낅뜲?댄듃 ??愿??Redis 罹먯떆 臾댄슚??
     */
    @Override
    @Transactional
    public void assignWorkerToZone(AdminZoneAssignmentRequest request) {
        Long workerId = request.getWorkerId();
        Long zoneId = request.getZoneId();

        log.info("[Redis Service] 援ъ뿭 諛곗젙 ?쒖옉: workerId={}, zoneId={}", workerId, zoneId);

        // 1. Validation
        if (!controlMapper.existsWorker(workerId)) {
            throw new RuntimeException("議댁옱?섏? ?딅뒗 ?묒뾽?먯엯?덈떎.");
        }
        if (!controlMapper.existsZone(zoneId)) {
            throw new RuntimeException("議댁옱?섏? ?딅뒗 援ъ뿭?낅땲??");
        }

        // 2. DB ?낅뜲?댄듃
        controlMapper.closeActiveAssignment(workerId);
        controlMapper.insertAssignmentHistory(workerId, zoneId, request.getReason());
        controlMapper.updateUserAssignedZone(workerId, zoneId);

        // 3. Rebalance snapshot refresh (apply latest zone assignment)
        try {
            BatchVO currentBatch = batchMapper.findCurrentInProgress();
            if (currentBatch != null) {
                rebalanceMapper.insertSnapshotFromLatestWithUserZone(currentBatch.getBatchId());
            }
        } catch (Exception e) {
            log.warn("[Redis Service] Snapshot refresh failed after assignment: {}", e.getMessage());
        }

        // 4. Redis 罹먯떆 臾댄슚??(以묒슂!)
        try {
            // ?묒뾽??罹먯떆 ??젣
            redisRepository.deleteWorkerCache(workerId);

            // ?곹깭 怨꾩궛? ?꾩껜 援ъ뿭 遺꾪룷???곹뼢 -> ?꾩껜 罹먯떆 臾댄슚??
            redisRepository.deleteAllControlCache();

            // Warm caches immediately for instant UI reflection
            List<ZoneOverviewDto> overviews = controlMapper.selectZoneOverviews();
            for (ZoneOverviewDto dto : overviews) {
                dto.setZoneName(ZoneType.getNameById(dto.getZoneId()));
                redisRepository.saveZoneOverview(dto.getZoneId(), dto);
            }

            DashboardSummaryDto summary = DashboardSummaryDto.builder()
                    .totalActiveWorkers(controlMapper.countTotalActiveWorkers())
                    .pendingIssues(controlMapper.countPendingIssues())
                    .completedIssues(controlMapper.countTodayCompletedIssues())
                    .totalProgressRate(0.0)
                    .zoneSummaries(overviews)
                    .build();
            redisRepository.saveDashboardSummary(summary);

            log.info("[Redis Service] 援ъ뿭 諛곗젙 ?꾨즺 諛?罹먯떆 臾댄슚?? workerId={}, zoneId={}",
                    workerId, zoneId);
        } catch (Exception e) {
            log.error("[Redis Service] 罹먯떆 臾댄슚???ㅽ뙣 (DB???뺤긽 ?낅뜲?댄듃??: workerId={}, error={}",
                    workerId, e.getMessage());
        }
    }

    /**
     * 愿由ъ옄 紐⑸줉 議고쉶
     * - 愿由ъ옄 ?뺣낫???먯＜ 蹂寃쎈릺吏 ?딆쑝?? ?ㅼ떆媛??곹깭 二쇱엯???꾩슂?섎?濡?罹먯떛?섏? ?딆쓬
     */
    @Override
    public List<AdminResponseDto> getAdmins(Long zoneId, String name) {
        log.debug("[Redis Service] 愿由ъ옄 紐⑸줉 議고쉶: zoneId={}, name={} (罹먯떛 ????", zoneId, name);

        // 1. DB 議고쉶
        List<AdminQueryVo> adminVos = controlMapper.selectAdmins(zoneId, name);

        // 2. UserVO ?앹꽦 (?ㅼ떆媛??곹깭 二쇱엯??
        List<UserVO> userVOs = adminVos.stream()
                .map(vo -> {
                    UserVO userVO = new UserVO();
                    userVO.setUserId(vo.getAdminId());
                    return userVO;
                })
                .toList();

        // 3. ?ㅼ떆媛?Redis ?곹깭 二쇱엯
        userService.populateUserStatus(userVOs);

        // 4. VO ??DTO 留ㅽ븨
        return java.util.stream.IntStream.range(0, adminVos.size())
                .mapToObj(i -> {
                    AdminQueryVo vo = adminVos.get(i);
                    UserVO userVO = userVOs.get(i);

                    return AdminResponseDto.builder()
                            .adminId(vo.getAdminId())
                            .name(vo.getRawName())
                            .assignedZoneId(vo.getAssignedZoneId())
                            .zoneName(ZoneType.getNameById(vo.getAssignedZoneId()))
                            .currentStatus(userVO.getStatus()) // ?ㅼ떆媛?Redis ?곹깭
                            .build();
                })
                .toList();
    }

    /**
     * [Lazy Initialization]
     * ?곸쐞 諛곗튂媛 吏꾪뻾 以묒씪 ?? ?대떦 諛곗튂?????Redis 愿???ㅻ깄?룹씠 ?놁쑝硫?珥덇린???섑뻾.
     * SETNX瑜??ъ슜?섏뿬 ?ㅼ쨷 ?쒕쾭/?ㅻ젅???섍꼍?먯꽌??1?뚮쭔 ?ㅽ뻾?섎룄濡?蹂댁옣 (硫깅벑??.
     */
    private void ensureRedisInitialized() {
        try {
            BatchVO currentBatch = batchMapper.findCurrentInProgress();
            if (currentBatch == null) {
                return; // 吏꾪뻾 以묒씤 諛곗튂媛 ?놁쑝硫?珥덇린??遺덊븘??
            }

            String initKey = "lookie:control:batch:" + currentBatch.getBatchId() + ":initialized";
            // 24?쒓컙 TTL ?ㅼ젙 (諛곗튂 湲몄씠???곕씪 議곗젙 媛??
            Boolean isNew = stringRedisTemplate.opsForValue().setIfAbsent(initKey, "true", 24, TimeUnit.HOURS);

            if (Boolean.TRUE.equals(isNew)) {
                log.info("[Redis Service] 珥덇린???쒖옉: batchId={}", currentBatch.getBatchId());
                initializeZoneProgress(currentBatch.getBatchId());
            }
        } catch (Exception e) {
            log.error("[Redis Service] 珥덇린??以??ㅻ쪟 諛쒖깮 (臾댁떆?섍퀬 吏꾪뻾)", e);
        }
    }

    /**
     * ?ㅼ젣 珥덇린??濡쒖쭅
     * - DB?먯꽌 ?꾩옱 ?곹깭(Total, Completed)瑜?議고쉶?섏뿬 Redis???ㅻ깄?????
     */
    private void initializeZoneProgress(Long batchId) {
        // 紐⑤뱺 Zone 議고쉶
        List<ZoneOverviewDto> zones = controlMapper.selectZoneOverviews();

        for (ZoneOverviewDto zone : zones) {
            Long zoneId = zone.getZoneId();

            // DB 吏묎퀎 (Total, Completed) - 寃곗젙?ы빆 1踰?
            int total = taskItemMapper.countItemsByBatchAndZone(batchId, zoneId);
            int completed = taskItemMapper.countCompletedItemsByBatchAndZone(batchId, zoneId);
            double progressRate = (total > 0) ? (double) completed * 100 / total : 0.0;
            // ?뚯닔??1?먮━ ?щ㎎??(?좏깮?ы빆?대굹 源붾걫?섍쾶 泥섎━)
            progressRate = Math.round(progressRate * 10.0) / 10.0;

            // Redis ???Key
            String progressKey = "lookie:control:zone:" + zoneId + ":progress";

            Map<String, String> data = new HashMap<>();
            data.put("batchId", String.valueOf(batchId));
            data.put("total", String.valueOf(total));
            data.put("completed", String.valueOf(completed));
            data.put("progressRate", String.valueOf(progressRate));

            stringRedisTemplate.opsForHash().putAll(progressKey, data);
            // 상위 배치 종료 후에도 무한 잔존하지 않도록 24시간 TTL 설정 (설계 의도 반영)
            stringRedisTemplate.expire(progressKey, PROGRESS_KEY_TTL_HOURS, TimeUnit.HOURS);

            // Overview ?ㅼ뿉??吏꾪뻾瑜?諛섏쁺 (Cache-Aside 濡쒖쭅怨??명솚???좎?)
            // 李멸퀬: WorkerMonitoringService媛 罹먯떆 誘몄뒪 ??DB媛믪쓣 ??뼱?????덉쑝誘濡?Cache-Aside),
            // ?ш린?쒕뒗 ProgressRate留??낅뜲?댄듃?섍굅?? Overview DTO ?먯껜瑜?罹먯떆 ?뚮컢???섎룄 ?덉쓬.
            // ?대쾲 援ы쁽?먯꽌??"?붾㈃??吏묎퀎"??progressKey瑜?硫붿씤?쇰줈 ?섍퀬,
            // Overview DTO 罹먯떆??Cache-Aside ?먮쫫??留↔린?? 吏꾪뻾瑜?媛?媛깆떊???꾩슂?섎㈃ 蹂꾨룄濡?泥섎━.
            // ?쇰떒 progressKey ?앹꽦???듭떖.

            log.info("[Redis Service] Zone Init: zoneId={}, total={}, completed={}, rate={}",
                    zoneId, total, completed, progressRate);
        }
    }
}
