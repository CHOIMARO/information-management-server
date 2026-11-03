package io.github.qkqnfld.information_management.common.security

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * DB에 저장할 비밀 값(예: Apple refresh 토큰)을 암호화·복호화한다.
 *
 * 왜 필요한가: Apple refresh 토큰이 있으면 그 사용자의 Apple 연결을 다룰 수 있다.
 * DB 백업이나 덤프가 새어도 토큰을 바로 쓸 수 없게, 평문 대신 암호문을 저장한다.
 * 키는 환경변수(TOKEN_ENCRYPTION_KEY)로만 주입하고 DB와 따로 보관한다.
 *
 * 방식: AES-256-GCM. GCM은 암호화와 함께 위조 검사(인증 태그)를 한다.
 * 같은 평문도 매번 다른 암호문이 나오도록 암호화마다 새 IV(12바이트 난수)를 만들어 암호문 앞에 붙인다.
 * 저장 형식: Base64(IV + 암호문 + 태그).
 */
@Component
class TokenCipher(
    /** Base64로 인코딩한 32바이트 키. 비어 있으면 암호화를 쓸 수 없다. */
    @Value("\${security.token-encryption-key:}") base64Key: String,
) {

    private val log = LoggerFactory.getLogger(TokenCipher::class.java)

    private val key: SecretKeySpec? = base64Key.trim().takeIf { it.isNotEmpty() }?.let {
        val bytes = Base64.getDecoder().decode(it)
        require(bytes.size == KEY_BYTES) { "security.token-encryption-key는 32바이트 키를 Base64로 인코딩한 값이어야 합니다" }
        SecretKeySpec(bytes, "AES")
    }

    private val random = SecureRandom()

    init {
        if (key == null) {
            log.warn("security.token-encryption-key(TOKEN_ENCRYPTION_KEY)가 비어 있다. Apple 토큰을 저장하지 않으므로 탈퇴할 때 Apple 토큰을 취소할 수 없다")
        }
    }

    /** 키가 설정되어 있는지. 없으면 encrypt/decrypt를 부르지 않는다. */
    val isConfigured: Boolean get() = key != null

    fun encrypt(plainText: String): String {
        val secretKey = checkNotNull(key) { "암호화 키가 설정되지 않았습니다" }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    /** @throws javax.crypto.AEADBadTagException 키가 다르거나 값이 바뀌었을 때 */
    fun decrypt(encoded: String): String {
        val secretKey = checkNotNull(key) { "암호화 키가 설정되지 않았습니다" }
        val bytes = Base64.getDecoder().decode(encoded)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        val plain = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        return String(plain, Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
