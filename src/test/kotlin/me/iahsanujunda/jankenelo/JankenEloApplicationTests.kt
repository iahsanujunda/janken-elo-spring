package me.iahsanujunda.jankenelo

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class JankenEloApplicationTests {

    @Test
    fun contextLoads() {
    }

}
