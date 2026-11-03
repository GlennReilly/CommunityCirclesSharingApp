package circles.server.data

import java.sql.Connection

/** One numbered SQL file under `db/migration` in the jar. */
data class Migration(val version: Int, val resource: String)

/** All migrations in order. Add new ones at the end; never edit a released one. */
val MIGRATIONS: List<Migration> = listOf(Migration(1, "V1__init.sql"), Migration(2, "V2__circle_epoch.sql"), Migration(3, "V3__steward_quorum.sql"), Migration(4, "V4__blobs_per_circle.sql"), Migration(5, "V5__steward_settings.sql"))

/** What a database looks like against the migrations this build knows. */
sealed interface SchemaState {
    /** No schema at all (a brand-new file). */
    data object Empty : SchemaState

    data class Behind(val current: Int, val latest: Int) : SchemaState

    data object Current : SchemaState

    /** The database was written by a newer build; this one must not touch it. */
    data class Ahead(val current: Int, val latest: Int) : SchemaState
}

/** Applies numbered SQL files in order and records the version in `schema_version` (spec 4.2). */
class Migrator(private val connection: Connection, private val migrations: List<Migration> = MIGRATIONS) {
    val latest: Int get() = migrations.maxOfOrNull { it.version } ?: 0

    fun currentVersion(): Int {
        if (!tableExists(VERSION_TABLE)) return 0
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM $VERSION_TABLE").use { rows ->
                return if (rows.next()) rows.getInt(1) else 0
            }
        }
    }

    fun state(): SchemaState {
        val current = currentVersion()
        return when {
            current == 0 -> SchemaState.Empty
            current < latest -> SchemaState.Behind(current, latest)
            current > latest -> SchemaState.Ahead(current, latest)
            else -> SchemaState.Current
        }
    }

    /** Applies every migration newer than the current version; returns the versions applied. */
    fun migrate(): List<Int> {
        val current = currentVersion()
        val pending = migrations.filter { it.version > current }.sortedBy { it.version }
        pending.forEach { apply(it) }
        return pending.map { it.version }
    }

    private fun apply(migration: Migration) {
        val sql = Migrator::class.java.getResourceAsStream("/$RESOURCE_DIR/${migration.resource}")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("migration file ${migration.resource} is missing from the jar")
        val wasAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement -> splitStatements(sql).forEach { statement.executeUpdate(it) } }
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM $VERSION_TABLE")
                statement.executeUpdate("INSERT INTO $VERSION_TABLE (version) VALUES (${migration.version})")
            }
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = wasAutoCommit
        }
    }

    private fun tableExists(name: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { it.next() }
        }

    companion object {
        private const val VERSION_TABLE = "schema_version"
        private const val RESOURCE_DIR = "db/migration"

        /**
         * Splits a migration file into statements: drops `--` comments (whole-line or trailing), then splits on `;`.
         * Migration files must not contain `--` or `;` inside string literals.
         */
        fun splitStatements(sql: String): List<String> =
            sql.lines().joinToString("\n") { it.substringBefore("--") }
                .split(';').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
