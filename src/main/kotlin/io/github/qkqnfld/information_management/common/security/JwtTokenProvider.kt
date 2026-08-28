package io.github.qkqnfld.information_management.common.security

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Date
import javax.crypto.SecretKey

/**
 * JWT 액세스 토큰 발급/검증.
 * 토큰의 subject에 회원 id를 담고, 서버만 아는 비밀키로 서명한다.
 * 서명 덕분에 서버는 상태(세션)를 저장하지 않고도 토큰이 위조되지 않았음을 검증할 수 있다.
 */
@Component
class JwtTokenProvider(
    @Value("\${jwt.secret}") secret: String,
    @Value("\${jwt.access-expiration-ms}") private val expirationMs: Long,
) {

    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))

    /** 회원 id를 담은 서명된 토큰을 발급한다. */
    fun createToken(memberId: Long): String {
        val now = Date()
        return Jwts.builder()
            .subject(memberId.toString())
            .issuedAt(now)
            .expiration(Date(now.time + expirationMs))
            .signWith(key)
            .compact()
    }

    /**
     * 토큰을 검증하고 회원 id를 꺼낸다. 서명이 다르거나 만료됐으면 null.
     * 인증 실패의 응답 변환은 시큐리티 계층이 담당하므로 여기서는 예외를 던지지 않는다.
     */
    fun parseMemberId(token: String): Long? {
        return try {
            Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload
                .subject
                .toLong()
        } catch (e: JwtException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }
}
