package org.example.pipeline.db

import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.install
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

class DatabaseConfigTest : FunSpec({

    val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    install(TestContainerSpecExtension(postgres))

    // Close any DataSource created by a test to prevent connection pool leaks
    var lastDataSource: DataSource? = null

    afterEach {
        runCatching { (lastDataSource as? HikariDataSource)?.close() }
        lastDataSource = null
    }

    context("successful init") {
        test("init returns a HikariDataSource") {
            val ds = DatabaseConfig.init(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password
            )
            lastDataSource = ds
            ds.shouldBeInstanceOf<HikariDataSource>()
        }

        test("Flyway migrations are applied") {
            val ds = DatabaseConfig.init(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password
            )
            lastDataSource = ds
            // Query the Flyway schema history table to verify migrations ran
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("SELECT COUNT(*) FROM flyway_schema_history")
                    rs.next()
                    val count = rs.getInt(1)
                    (count > 0) shouldBe true
                }
            }
        }

        test("Exposed connection is functional after init") {
            val ds = DatabaseConfig.init(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password
            )
            lastDataSource = ds
            // Exposed should be wired up and able to execute queries
            val result = newSuspendedTransaction {
                exec("SELECT 1 AS result") { rs ->
                    rs.next()
                    rs.getInt("result")
                }
            }
            result shouldBe 1
        }
    }

    context("connection failures") {
        test("throws on invalid JDBC URL") {
            // Use TEST-NET address (RFC 5737) which is unreachable
            shouldThrow<Exception> {
                val ds = DatabaseConfig.init(
                    jdbcUrl = "jdbc:postgresql://192.0.2.1:5432/nonexistent",
                    username = "fake",
                    password = "fake"
                )
                lastDataSource = ds
            }
        }

        test("throws on invalid credentials") {
            // Valid URL from testcontainer but wrong password
            shouldThrow<Exception> {
                val ds = DatabaseConfig.init(
                    jdbcUrl = postgres.jdbcUrl,
                    username = postgres.username,
                    password = "definitely-wrong-password"
                )
                lastDataSource = ds
            }
        }
    }

    context("edge cases") {
        test("small maxPoolSize=2 works") {
            val ds = DatabaseConfig.init(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password,
                maxPoolSize = 2
            )
            lastDataSource = ds
            ds.shouldBeInstanceOf<HikariDataSource>()
            (ds as HikariDataSource).maximumPoolSize shouldBe 2
        }

        test("default maxPoolSize works") {
            val ds = DatabaseConfig.init(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password
            )
            lastDataSource = ds
            ds.shouldBeInstanceOf<HikariDataSource>()
            // Default is 10 per DatabaseConfig.init signature
            (ds as HikariDataSource).maximumPoolSize shouldBe 10
        }
    }
})
