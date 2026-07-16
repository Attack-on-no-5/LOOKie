package lookie.backend.global.config;

import lookie.backend.global.security.JwtFilter;
import lookie.backend.global.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * [Spring Security 핵심 설정 파일]
 * - 인증(Authentication) 및 인가(Authorization) 전반적인 규칙 정의
 * - JWT 필터 등록, 세션 정책 설정, 예외 처리 등 담당
 */
@Configuration
@EnableWebSecurity // 스프링 시큐리티의 웹 보안 기능 활성화
@RequiredArgsConstructor
public class SecurityConfig {

        private final JwtProvider jwtProvider;
        private final CorsConfigurationSource corsConfigurationSource; // CorsConfig에서 등록한 빈을 주입받음
        private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate; // Redis 블랙리스트 확인용

        /**
         * [비밀번호 암호화 빈 등록]
         * - BCrypt 알고리즘을 사용하여 비밀번호를 안전하게 해싱
         */
        @Bean
        public PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder();
        }

        /**
         * [보안 필터 체인(Security Filter Chain) 설정]
         * - HTTP 요청이 들어올 때 거쳐야 하는 보안 필터들의 순서와 규칙 정의
         */
        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                http
                                // 1. CSRF 비활성화
                                // - REST API는 Stateless(무상태)하므로 브라우저 세션 기반의 CSRF 보호가 불필요함
                                .csrf(csrf -> csrf.disable())

                                // 2. CORS 설정 적용
                                // - 별도로 분리된 CorsConfig의 설정을 적용합니다.
                                .cors(cors -> cors.configurationSource(corsConfigurationSource))

                                // 3. 기본 인증 방식 비활성화 (JWT 사용)
                                // - UI를 통한 Form Login과 HTTP Basic 인증을 사용하지 않음
                                .formLogin(form -> form.disable())
                                .httpBasic(basic -> basic.disable())

                                // 4. 세션 관리 정책 설정 (Stateless)
                                // - 서버에 세션을 유지하지 않고, 요청마다 토큰을 검증하여 인증 진행
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                                // 5. URL별 접근 권한 관리 (인가)
                                .authorizeHttpRequests(auth -> auth
                                                // [화이트리스트] 인증 없이 접근 가능한 경로
                                                .requestMatchers(
                                                                "/api/auth/signup", // 회원가입
                                                                "/api/auth/login", // 로그인
                                                                "/api/auth/check/**", // 중복 확인
                                                                "/api/auth/email/**", // 이메일 인증
                                                                "/api/auth/password/**", // 비밀번호 찾기
                                                                // "/api/tasks/**", // 작업 관련 API (개발용)
                                                                "/api/webrtc/**",
                                                                "/api/issues/*/ai/result", // AI Webhook (인증 제외 -
                                                                // 개발용)
                                                                "/api/auth/refresh" // 토큰 재발급
                                                ).permitAll()

                                                // [공개 리소스] 소켓 통신 및 Swagger 문서
                                                .requestMatchers("/api/realtime/**").permitAll()
                                                .requestMatchers("/actuator/**").permitAll() // 로컬 실험 한정 — 프로덕션은 별도 인증 필요 (Phase 0 관측성)
                                                .requestMatchers("/images/**").permitAll() // ✅ 이미지 정적 리소스는 누구나 조회 가능해야
                                                                                           // 함
                                                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                                                "/swagger-resources/**")
                                                .permitAll()

                                                // [그 외] 나머지 모든 요청은 인증된 사용자만 접근 가능 (로그아웃 포함)
                                                .anyRequest().authenticated())

                                // 6. JWT 인증 필터 추가
                                // - UsernamePasswordAuthenticationFilter(기본 로그인 필터) 앞에 JwtFilter 배치
                                // - 요청이 들어오면 스프링 시큐리티가 인증을 확인하기 전에 JWT 토큰 유효성을 먼저 검사
                                .addFilterBefore(new JwtFilter(jwtProvider, redisTemplate),
                                                UsernamePasswordAuthenticationFilter.class);

                return http.build();
        }
}