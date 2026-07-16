package lookie.backend.infra.ai;

import lombok.extern.slf4j.Slf4j;
import lookie.backend.infra.ai.dto.AiAnalysisRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class AiAnalysisClient {

    private final RestTemplate restTemplate;

    @Value("${ai.server.url}")
    private String aiServerUrl;

    public AiAnalysisClient(@Qualifier("aiRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * AI 서버로 분석 요청 (비동기)
     * - Fire and Forget 방식
     * - 결과는 Webhook으로 수신
     * - DAMAGED: 이미지 분석, OUT_OF_STOCK: 재고 상태 분석
     */
    public void requestAnalysis(AiAnalysisRequest request) {
        String url = aiServerUrl + "/predict";

        log.info("[AiAnalysisClient] Sending analysis request to AI Server. " +
                        "url={}, issueId={}, productId={}, issueType={}, hasInventoryState={}",
                url, 
                request.getIssueId(), 
                request.getProductId(),
                request.getIssueType(),
                request.getInventoryState() != null);

        try {
            // POST 요청 (JSON Body)
            // RestTemplate은 객체를 보내면 자동으로 JSON 변환 (MessageConverter)
            // 응답은 무시 (Webhook으로 오므로)
            restTemplate.postForObject(url, request, String.class);

            log.info("[AiAnalysisClient] Request sent successfully. issueId={}, issueType={}", 
                    request.getIssueId(), request.getIssueType());
        } catch (Exception e) {
            log.error("[AiAnalysisClient] Failed to send request to AI Server. " +
                            "issueId={}, issueType={}, error={}",
                    request.getIssueId(), request.getIssueType(), e.getMessage(), e);
            // 비동기이므로 예외를 던져도 잡을 곳이 마땅치 않음. 로그만 남김.
            // 필요시 재시도 로직이나 DLQ 처리 추가 가능
        }
    }
}
