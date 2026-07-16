package lookie.backend.infra.ai;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * AI 서버 호출용 RestTemplate 설정.
 * - 기존엔 각 클라이언트가 {@code new RestTemplate()}을 직접 생성해 타임아웃이 무제한이었음.
 * - AI 서버 지연 시 호출 스레드가 무한 대기하지 않도록 connect/read 타임아웃을 외부화하여 적용.
 */
@Configuration
public class AiClientConfig {

    @Bean
    public RestTemplate aiRestTemplate(
            RestTemplateBuilder builder,
            @Value("${ai.client.connect-timeout:3s}") Duration connectTimeout,
            @Value("${ai.client.read-timeout:5s}") Duration readTimeout) {
        return builder
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .build();
    }
}
