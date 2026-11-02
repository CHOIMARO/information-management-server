package io.github.qkqnfld.information_management.common.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * API 문서(OpenAPI) 설정.
 *
 * springdoc이 컨트롤러와 DTO를 읽어 경로·요청·응답 형식을 자동으로 만든다. 이 클래스는 공통 정보만 더한다.
 * - JWT 인증 방식을 등록한다. Swagger UI의 Authorize 버튼에 access 토큰을 넣으면 이후 요청에 자동으로 붙는다.
 * - 로그인이 필요한 API에만 인증 표시(자물쇠)를 단다.
 *
 * 문서는 개발용이다. 운영에서는 환경변수로 springdoc을 끈다 (docs/ec2-setup.md 6-1).
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("magic-editor API")
                .version("v1")
                .description(
                    """
                    로그인 API로 받은 accessToken을 Authorize 버튼에 넣으면 인증이 필요한 API를 호출할 수 있다.

                    오류 응답은 모든 API가 같은 형식이다: {"code": "...", "message": "...", "fieldErrors": [...]}
                    앱은 HTTP 상태 코드와 code 값으로 오류 종류를 구분한다.
                    """.trimIndent(),
                ),
        )
        .components(
            Components().addSecuritySchemes(
                BEARER_AUTH,
                SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
            ),
        )

    /**
     * 로그인이 필요한 API에 인증 요구 사항을 단다.
     * 공개 API의 기준은 SecurityConfig의 permitAll 목록과 같아야 한다. 그쪽을 바꾸면 여기도 바꾼다.
     */
    @Bean
    fun securityRequirementCustomizer(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        openApi.paths?.forEach { (path, pathItem) ->
            pathItem.readOperationsMap().forEach { (method, operation) ->
                if (!isPublic(method, path)) {
                    operation.addSecurityItem(SecurityRequirement().addList(BEARER_AUTH))
                }
            }
        }
    }

    private fun isPublic(method: PathItem.HttpMethod, path: String): Boolean =
        path.startsWith("/auth/") || (method == PathItem.HttpMethod.POST && path == "/members")

    companion object {
        private const val BEARER_AUTH = "bearerAuth"
    }
}
