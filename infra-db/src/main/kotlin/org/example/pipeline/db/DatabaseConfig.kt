package org.example.pipeline.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Database configuration and initialization.
 *
 * Handles HikariCP connection pooling, Flyway migrations, and Exposed database setup.
 */
object DatabaseConfig {

    private val logger = LoggerFactory.getLogger(DatabaseConfig::class.java)

    /** Timeout in milliseconds for connection validation queries. */
    private const val VALIDATION_TIMEOUT_MS = 3000L

    /**
     * Initializes the database connection pool and runs migrations.
     *
     * @param jdbcUrl PostgreSQL JDBC URL
     * @param username Database username
     * @param password Database password
     * @param maxPoolSize Maximum connection pool size (default: 10)
     * @return The configured DataSource
     */
    fun init(
        jdbcUrl: String,
        username: String,
        password: String,
        maxPoolSize: Int = 10
    ): DataSource {
        logger.info("Initializing database connection to: $jdbcUrl")

        val dataSource = createHikariDataSource(jdbcUrl, username, password, maxPoolSize)
        runMigrations(dataSource)
        connectExposed(dataSource)

        logger.info("Database initialization complete")
        return dataSource
    }

    private fun createHikariDataSource(
        jdbcUrl: String,
        username: String,
        password: String,
        maxPoolSize: Int
    ): HikariDataSource {
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            this.maximumPoolSize = maxPoolSize
            this.isAutoCommit = false
            this.driverClassName = "org.postgresql.Driver"

            // Connection validation
            this.connectionTestQuery = "SELECT 1"
            this.validationTimeout = VALIDATION_TIMEOUT_MS

            // Pool naming for monitoring
            this.poolName = "DocumentPipelinePool"
        }
        return HikariDataSource(config)
    }

    private fun runMigrations(dataSource: DataSource) {
        logger.info("Running Flyway migrations...")

        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()

        val result = flyway.migrate()
        logger.info("Applied ${result.migrationsExecuted} migrations")
    }

    private fun connectExposed(dataSource: DataSource) {
        Database.connect(dataSource)
        logger.info("Exposed database connection established")
    }
}
