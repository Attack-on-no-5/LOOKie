package lookie.backend.infra.ai;

import lombok.extern.slf4j.Slf4j;
import lookie.backend.domain.control.vo.RebalanceSnapshotVO;
import lookie.backend.infra.ai.dto.RebalanceRecommendRequest;
import lookie.backend.infra.ai.dto.RebalanceRecommendResponse;
import lookie.backend.infra.ai.dto.SnapshotRow;
import lookie.backend.infra.ai.dto.RebalanceRecommendRequest.MoveInput;
import lookie.backend.domain.control.dto.ZoneMoveRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

// removed unused import
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AiRebalanceClient {

    private final RestTemplate restTemplate;

    @Value("${ai.server.url}")
    private String aiServerUrl;

    public AiRebalanceClient(@Qualifier("aiRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * AI ?쒕쾭???щ같移?異붿쿇 ?붿껌
     * 
     * @param snapshots ?꾩옱 諛곗튂??理쒖떊 ?ㅻ깄??由ъ뒪??
     * @return AI 異붿쿇 寃곌낵 (moves ??
     */
    public RebalanceRecommendResponse requestRecommend(List<RebalanceSnapshotVO> snapshots) {
        String url = aiServerUrl + "/rebalance/recommend";

        // 1. VO -> DTO 蹂??(Snake Case Mapping???꾪빐)
        List<SnapshotRow> rows = snapshots.stream()
                .map(this::convertToRow)
                .collect(Collectors.toList());

        RebalanceRecommendRequest request = RebalanceRecommendRequest.builder()
                .rows(rows)
                .build();

        log.info("[AiRebalanceClient] Requesting recommend from AI Server. url={}, rowCount={}", url, rows.size());

        try {
            // 2. POST ?붿껌
            RebalanceRecommendResponse response = restTemplate.postForObject(url, request,
                    RebalanceRecommendResponse.class);
            log.info("[AiRebalanceClient] Received response. moves={}",
                    (response != null && response.getMoves() != null) ? response.getMoves().size() : 0);
            return response;
        } catch (Exception e) {
            log.error("[AiRebalanceClient] Failed to request recommend. error={}", e.getMessage(), e);
            throw new RuntimeException("AI ?쒕쾭 ?몄텧 以??ㅻ쪟媛 諛쒖깮?덉뒿?덈떎.", e);
        }
    }

    public RebalanceRecommendResponse requestRecommendWithMoves(List<RebalanceSnapshotVO> snapshots, List<ZoneMoveRequest> moves) {
        String url = aiServerUrl + "/rebalance/recommend";

        List<SnapshotRow> rows = snapshots.stream()
                .map(this::convertToRow)
                .collect(Collectors.toList());

        List<MoveInput> moveInputs = moves == null ? List.of() : moves.stream()
                .map(m -> MoveInput.builder()
                        .workerId(m.getWorkerId())
                        .toZone(m.getToZoneId())
                        .build())
                .collect(Collectors.toList());

        RebalanceRecommendRequest request = RebalanceRecommendRequest.builder()
                .rows(rows)
                .moves(moveInputs)
                .build();

        log.info("[AiRebalanceClient] Requesting recommend (with moves) from AI Server. url={}, rowCount={}, moveCount={}",
                url, rows.size(), moveInputs.size());

        try {
            RebalanceRecommendResponse response = restTemplate.postForObject(url, request,
                    RebalanceRecommendResponse.class);
            log.info("[AiRebalanceClient] Received response. moves={}",
                    (response != null && response.getMoves() != null) ? response.getMoves().size() : 0);
            return response;
        } catch (Exception e) {
            log.error("[AiRebalanceClient] Failed to request recommend (with moves). error={}", e.getMessage(), e);
            throw new RuntimeException("AI ?쒕쾭 ?몄텧 以??ㅻ쪟媛 諛쒖깮?덉뒿?덈떎.", e);
        }
    }

    private SnapshotRow convertToRow(RebalanceSnapshotVO vo) {
        return SnapshotRow.builder()
                .ts(vo.getTs().format(DateTimeFormatter.ISO_DATE_TIME)) // ISO 8601
                .batchId(vo.getBatchId())
                .workerId(vo.getWorkerId())
                .zoneId(vo.getZoneId())
                .progress(vo.getProgress())
                .remainingQty(vo.getRemainingQty())
                .timeToPlannedEndMin(vo.getTimeToPlannedEndMin())
                .timeToDeadlineMin(vo.getTimeToDeadlineMin())
                .zoneBacklog(vo.getZoneBacklog())
                .zoneActiveWorkers(vo.getZoneActiveWorkers())
                .zoneLocationCnt(vo.getZoneLocationCnt())
                .zoneBlockingIssueCnt(vo.getZoneBlockingIssueCnt())
                .workerSpeed30mAvg(vo.getWorkerSpeed30mAvg())
                .speedLabel(vo.getSpeedLabel())
                .pickedTotal(vo.getPickedTotal())
                .requiredTotal(vo.getRequiredTotal())
                .build();
    }
}
