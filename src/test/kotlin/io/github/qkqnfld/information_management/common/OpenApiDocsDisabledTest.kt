package io.github.qkqnfld.information_management.common

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/**
 * 운영 설정(문서 끔)에서 API 문서가 열리지 않는지 검증한다.
 * 운영 환경변수 SPRINGDOC_API_DOCS_ENABLED=false, SPRINGDOC_SWAGGER_UI_ENABLED=false와 같은 설정이다.
 */
@SpringBootTest(
    properties = [
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false",
    ],
)
@AutoConfigureMockMvc
class OpenApiDocsDisabledTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `문서를 끄면 문서 JSON과 Swagger UI 모두 404다`() {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound)
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound)
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound)
    }
}
