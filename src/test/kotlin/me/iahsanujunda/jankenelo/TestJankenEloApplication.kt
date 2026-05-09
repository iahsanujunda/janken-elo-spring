package me.iahsanujunda.jankenelo

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
    fromApplication<JankenEloApplication>().with(TestcontainersConfiguration::class).run(*args)
}
