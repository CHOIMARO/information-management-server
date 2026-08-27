package io.github.qkqnfld.information_management

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class HelloController {

    @GetMapping("/hello")
    fun hello(): String {
        return "안녕하세요! 첫 번째 서버 응답입니다."
    }

    @GetMapping("/greet")
    fun greet(@RequestParam(defaultValue = "손님") name: String): Map<String, String> {
        return mapOf(
            "message" to "반갑습니다, ${name}님!",
            "from" to "information-management server",
        )
    }
}
