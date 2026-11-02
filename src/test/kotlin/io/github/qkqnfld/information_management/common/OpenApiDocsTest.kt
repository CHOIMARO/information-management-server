package io.github.qkqnfld.information_management.common

import io.github.qkqnfld.information_management.dubbing.infrastructure.AudioStorage
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/**
 * API 문서(springdoc) 테스트.
 * 문서가 로그인 없이 열리는지, 필요한 API만 들어 있는지, 인증 표시가 SecurityConfig와 맞는지 검증한다.
 * 더빙 API 테스트와 같은 mock 구성을 써서 테스트 컨텍스트를 공유한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var ttsClient: TtsClient

    @MockitoBean
    private lateinit var audioStorage: AudioStorage

    @Test
    fun `문서 JSON은 로그인 없이 열리고 인증과 더빙 API를 담는다`() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/auth/login']").exists())
            .andExpect(jsonPath("$.paths['/voices']").exists())
            .andExpect(jsonPath("$.paths['/dubbing-jobs']").exists())
            .andExpect(jsonPath("$.paths['/dubbing-jobs/{id}']").exists())
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
    }

    @Test
    fun `제거 예정인 메모 API는 문서에서 빠진다`() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/memos']").doesNotExist())
            .andExpect(jsonPath("$.paths['/memos/{id}']").doesNotExist())
            .andExpect(jsonPath("$.paths['/tags']").doesNotExist())
    }

    @Test
    fun `로그인이 필요한 API에만 인증 표시가 붙는다`() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            // 공개 API: SecurityConfig의 permitAll 목록
            .andExpect(jsonPath("$.paths['/members'].post.security").doesNotExist())
            .andExpect(jsonPath("$.paths['/auth/login'].post.security").doesNotExist())
            .andExpect(jsonPath("$.paths['/auth/refresh'].post.security").doesNotExist())
            // 로그인이 필요한 API
            .andExpect(jsonPath("$.paths['/members/me'].get.security[0].bearerAuth").exists())
            .andExpect(jsonPath("$.paths['/dubbing-jobs'].post.security[0].bearerAuth").exists())
            .andExpect(jsonPath("$.paths['/voices'].get.security[0].bearerAuth").exists())
    }

    @Test
    fun `문서는 앱이 실제로 주고받는 형식과 같다`() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            // 더빙 요청은 비동기 접수라서 200이 아니라 202다
            .andExpect(jsonPath("$.paths['/dubbing-jobs'].post.responses['202']").exists())
            .andExpect(jsonPath("$.paths['/dubbing-jobs'].post.responses['200']").doesNotExist())
            // 공통 예외 처리기의 오류 전체가 모든 API에 붙지 않는다
            .andExpect(jsonPath("$.paths['/voices'].get.responses['409']").doesNotExist())
            // 검증용 계산 값은 요청 필드가 아니다
            .andExpect(jsonPath("$.components.schemas.CreateDubbingJobRequest.properties.voiceSelectedOnce").doesNotExist())
            .andExpect(jsonPath("$.components.schemas.RegisterVoiceRequest.properties.fileNotEmpty").doesNotExist())
            // 페이지 요청은 page, size, sort 쿼리 파라미터다
            .andExpect(jsonPath("$.paths['/dubbing-jobs'].get.parameters[?(@.name == 'page')].in").value("query"))
            .andExpect(jsonPath("$.paths['/dubbing-jobs'].get.parameters[?(@.name == 'size')]").exists())
            .andExpect(jsonPath("$.paths['/dubbing-jobs'].get.parameters[?(@.name == 'pageable')]").isEmpty)
    }

    @Test
    fun `Swagger UI 화면은 로그인 없이 열린다`() {
        mockMvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk)
    }
}
