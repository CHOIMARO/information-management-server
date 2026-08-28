package io.github.qkqnfld.information_management.common.security

import io.github.qkqnfld.information_management.common.presentation.ErrorResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import tools.jackson.databind.ObjectMapper

/**
 * Spring Security 설정.
 * 토큰 기반 무상태(stateless) API 정책: 세션을 만들지 않고,
 * 매 요청을 JwtAuthenticationFilter가 검사한 결과로만 인증을 판단한다.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val objectMapper: ObjectMapper,
) {

    /** BCrypt 해싱기. 같은 비밀번호라도 매번 다른 해시가 나오는 적응형 단방향 해시다. */
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        return http
            // CSRF는 브라우저가 쿠키를 자동 전송하는 걸 악용하는 공격이다.
            // 토큰 방식은 쿠키를 쓰지 않으므로 공격 표면이 없어 끈다.
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    // 인증 없이 접근 가능한 문: 회원가입, 인증 관련 전체(/auth/**).
                    // /auth/**는 로그인 전(자체/SNS 로그인)이거나 access 만료 후(재발급/로그아웃)에
                    // 호출되는 API들이라 전부 열어둔다 — 각자 본문의 자격 증명이 검증 역할을 한다
                    .requestMatchers(HttpMethod.POST, "/members").permitAll()
                    .requestMatchers("/auth/**").permitAll()
                    // (개발용) API 테스트 페이지. 로그인 전에도 열려야 하므로 GET만 허용한다
                    .requestMatchers(HttpMethod.GET, "/test.html").permitAll()
                    // 그 외 전부는 로그인(유효한 토큰) 필수
                    .anyRequest().authenticated()
            }
            .exceptionHandling {
                // 필터 단계의 인증 실패는 GlobalExceptionHandler(MVC 안쪽)까지 오지 않으므로
                // 여기서 직접 같은 ErrorResponse 형식으로 응답한다.
                it.authenticationEntryPoint { _, response, _ ->
                    writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다")
                }
                it.accessDeniedHandler { _, response, _ ->
                    writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다")
                }
            }
            // 아이디/비밀번호 폼 로그인 필터 자리에 우리 토큰 필터를 끼워 넣는다
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()
    }

    private fun writeError(response: HttpServletResponse, status: Int, code: String, message: String) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(objectMapper.writeValueAsString(ErrorResponse(code = code, message = message)))
    }
}
