package io.github.qkqnfld.information_management.common.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 모든 요청에서 Authorization: Bearer 토큰을 검사하는 서블릿 필터.
 * 유효한 토큰이면 SecurityContext에 "이 요청은 회원 {id}가 인증됨"을 기록한다.
 * 여기서 기록하지 않으면 뒤의 인가 단계(authorizeHttpRequests)가 익명 요청으로 취급해 401을 낸다.
 * OncePerRequestFilter: 한 요청에 정확히 한 번만 실행되도록 보장하는 베이스 클래스.
 */
@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header != null && header.startsWith("Bearer ")) {
            val memberId = jwtTokenProvider.parseMemberId(header.removePrefix("Bearer "))
            if (memberId != null) {
                // principal에 회원 id를 담는다. 컨트롤러에서 @AuthenticationPrincipal로 꺼낸다.
                val authentication = UsernamePasswordAuthenticationToken(memberId, null, emptyList())
                SecurityContextHolder.getContext().authentication = authentication
            }
        }
        filterChain.doFilter(request, response)
    }
}
