package io.github.qkqnfld.information_management.member.infrastructure

import io.github.qkqnfld.information_management.member.domain.InvalidSnsTokenException
import io.jsonwebtoken.JwsHeader
import io.jsonwebtoken.LocatorAdapter
import io.jsonwebtoken.security.Jwks
import org.springframework.web.client.RestClient
import java.security.Key
import java.time.Duration
import java.time.Instant

/**
 * SNS가 공개한 서명용 공개키 목록(JWKS)으로 토큰 검증 키를 찾는다. Apple과 Google이 함께 쓴다.
 *
 * JWKS(JSON Web Key Set)란: "이 키들로 서명한 토큰만 우리가 발급한 것"이라고 SNS가 공개한 키 목록이다.
 * 토큰 헤더의 kid(키 id)로 목록에서 키를 고르고, 그 키로 서명을 검증한다.
 * SNS는 키를 가끔 교체한다. 그래서 모르는 kid가 오면 목록을 다시 받는다.
 *
 * @param keysUri 공개키 목록 주소. restClient에 baseUrl이 있으면 상대 경로도 된다.
 * @param refreshInterval 목록을 다시 받는 최소 간격. 가짜 kid로 SNS 호출을 반복시키는 것을 막는다.
 */
class JwksKeyLocator(
    private val restClient: RestClient,
    private val keysUri: String,
    private val refreshInterval: Duration,
) : LocatorAdapter<Key>() {

    /** kid → 공개키 */
    @Volatile
    private var keys: Map<String, Key> = emptyMap()

    @Volatile
    private var keysFetchedAt: Instant = Instant.EPOCH

    /** 토큰 헤더의 kid로 검증 키를 고른다. RS256 외의 알고리즘은 거절한다. */
    override fun locate(header: JwsHeader): Key {
        if (header.algorithm != SIGNATURE_ALGORITHM) throw InvalidSnsTokenException()
        val kid = header.keyId ?: throw InvalidSnsTokenException()
        return keys[kid] ?: refreshKeys()[kid] ?: throw InvalidSnsTokenException()
    }

    /**
     * 공개키 목록을 다시 받는다. 여러 요청이 동시에 와도 한 번만 받도록 잠근다.
     * 마지막으로 받은 지 refreshInterval이 지나지 않았으면 다시 받지 않고 지금 목록을 돌려준다.
     */
    @Synchronized
    private fun refreshKeys(): Map<String, Key> {
        val now = Instant.now()
        if (keys.isNotEmpty() && now.isBefore(keysFetchedAt.plus(refreshInterval))) {
            return keys
        }
        val json = restClient.get().uri(keysUri).retrieve().body(String::class.java)
            ?: throw InvalidSnsTokenException()
        val jwkSet = Jwks.setParser().build().parse(json)
        // JwkSet은 Map이기도 해서 Kotlin의 jwkSet.keys는 Map의 키 이름 목록이 된다. JWK 목록은 getKeys()로 꺼낸다.
        keys = jwkSet.getKeys().mapNotNull { jwk -> jwk.id?.let { kid -> kid to jwk.toKey() } }.toMap()
        keysFetchedAt = now
        return keys
    }

    companion object {
        private const val SIGNATURE_ALGORITHM = "RS256"
    }
}
