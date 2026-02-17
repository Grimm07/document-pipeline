package org.example.pipeline.api.routes

import com.rabbitmq.client.Connection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.sql.Connection as SqlConnection
import javax.sql.DataSource

/** Tests for the deep health check endpoint. */
class HealthRoutesTest : FunSpec({

    val mockDataSource = mockk<DataSource>()
    val mockRabbitConnection = mockk<Connection>()
    val mockSqlConnection = mockk<SqlConnection>()

    beforeEach {
        clearAllMocks()
    }

    fun ApplicationTestBuilder.setupHealthApp() {
        application {
            install(Koin) {
                modules(module {
                    single<DataSource> { mockDataSource }
                    single<Connection> { mockRabbitConnection }
                })
            }
            install(ContentNegotiation) {
                json(Json { prettyPrint = true; isLenient = true })
            }
            healthRoutes()
        }
    }

    test("GET /api/health returns 200 when all checks healthy") {
        testApplication {
            setupHealthApp()
            every { mockDataSource.connection } returns mockSqlConnection
            every { mockSqlConnection.isValid(any()) } returns true
            every { mockSqlConnection.close() } just Runs
            every { mockRabbitConnection.isOpen } returns true

            val response = client.get("/api/health")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"status\":\"ok\""
            body shouldContain "\"database\":\"ok\""
            body shouldContain "\"rabbitmq\":\"ok\""
        }
    }

    test("GET /api/health returns 503 when database is down") {
        testApplication {
            setupHealthApp()
            every { mockDataSource.connection } throws RuntimeException("Connection refused")
            every { mockRabbitConnection.isOpen } returns true

            val response = client.get("/api/health")
            response.status shouldBe HttpStatusCode.ServiceUnavailable
            val body = response.bodyAsText()
            body shouldContain "\"status\":\"degraded\""
            body shouldContain "\"database\":\"fail\""
            body shouldContain "\"rabbitmq\":\"ok\""
        }
    }

    test("GET /api/health returns 503 when rabbitmq is down") {
        testApplication {
            setupHealthApp()
            every { mockDataSource.connection } returns mockSqlConnection
            every { mockSqlConnection.isValid(any()) } returns true
            every { mockSqlConnection.close() } just Runs
            every { mockRabbitConnection.isOpen } returns false

            val response = client.get("/api/health")
            response.status shouldBe HttpStatusCode.ServiceUnavailable
            val body = response.bodyAsText()
            body shouldContain "\"status\":\"degraded\""
            body shouldContain "\"database\":\"ok\""
            body shouldContain "\"rabbitmq\":\"fail\""
        }
    }

    test("GET /api/health returns 503 when all dependencies are down") {
        testApplication {
            setupHealthApp()
            every { mockDataSource.connection } throws RuntimeException("Connection refused")
            every { mockRabbitConnection.isOpen } throws RuntimeException("Connection reset")

            val response = client.get("/api/health")
            response.status shouldBe HttpStatusCode.ServiceUnavailable
            val body = response.bodyAsText()
            body shouldContain "\"status\":\"degraded\""
            body shouldContain "\"database\":\"fail\""
            body shouldContain "\"rabbitmq\":\"fail\""
        }
    }
})
