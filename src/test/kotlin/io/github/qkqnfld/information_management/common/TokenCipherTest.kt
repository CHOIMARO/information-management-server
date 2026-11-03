package io.github.qkqnfld.information_management.common

import io.github.qkqnfld.information_management.common.security.TokenCipher
import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/** 비밀 값 암호화(AES-256-GCM) 단위 테스트. */
class TokenCipherTest {

    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val cipher = TokenCipher(key)

    @Test
    fun `암호화한 값을 복호화하면 원래 값이 된다`() {
        val encrypted = cipher.encrypt("com.app|apple-refresh-token")

        assertEquals("com.app|apple-refresh-token", cipher.decrypt(encrypted))
    }

    @Test
    fun `같은 값도 매번 다른 암호문이 나온다 (IV를 매번 새로 만든다)`() {
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }

    @Test
    fun `다른 키로는 복호화할 수 없다`() {
        val otherKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val encrypted = cipher.encrypt("secret")

        assertFailsWith<AEADBadTagException> { TokenCipher(otherKey).decrypt(encrypted) }
    }

    @Test
    fun `키가 없으면 설정되지 않은 상태가 되고 암호화를 거절한다`() {
        val empty = TokenCipher("")

        assertFalse(empty.isConfigured)
        assertFailsWith<IllegalStateException> { empty.encrypt("secret") }
    }

    @Test
    fun `키 길이가 32바이트가 아니면 서버가 시작되지 않게 거절한다`() {
        assertFailsWith<IllegalArgumentException> { TokenCipher(Base64.getEncoder().encodeToString(ByteArray(16))) }
    }
}
