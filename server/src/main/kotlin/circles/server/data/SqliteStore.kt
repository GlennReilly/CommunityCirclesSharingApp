package circles.server.data

import circles.api.StoredEvent
import circles.encoding.ByteString
import circles.event.DeviceId
import circles.event.DeviceRegistration
import circles.event.Envelope
import circles.event.EnvelopeDto
import circles.event.EventId
import circles.event.InviteTerms
import circles.policy.CircleId
import circles.policy.MemberId
import circles.server.domain.ChallengeRecord
import circles.server.domain.DeviceRecord
import circles.server.domain.Effect
import circles.server.domain.InviteRecord
import circles.server.domain.SessionRecord
import circles.server.domain.Store
import circles.server.domain.StoreTransaction
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import org.sqlite.SQLiteConfig

private const val BUSY_TIMEOUT_MILLIS = 5_000
private const val FIRST_SEQ = 1L

/** Opens SQLite databases the way the server wants them: WAL for files, immediate write transactions. */
object Database {
    const val FILE_NAME = "circles.db"

    /** A database file inside [dataDir] (created if needed). */
    fun openFile(dataDir: Path): Connection {
        Files.createDirectories(dataDir)
        return open("jdbc:sqlite:${dataDir.resolve(FILE_NAME)}", wal = true)
    }

    /** A private in-memory database; one connection, so the whole database lives and dies with it. */
    fun openMemory(): Connection = open("jdbc:sqlite::memory:", wal = false)

    private fun open(url: String, wal: Boolean): Connection {
        val config = SQLiteConfig().apply {
            enforceForeignKeys(true)
            setBusyTimeout(BUSY_TIMEOUT_MILLIS)
            setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
            if (wal) {
                setJournalMode(SQLiteConfig.JournalMode.WAL)
                setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
            }
        }
        return DriverManager.getConnection(url, config.toProperties())
    }
}

/** Hand-written SQL over one connection. All access goes through [transaction], which takes a lock. */
class SqliteStore(private val connection: Connection) : Store {
    private val lock = Any()

    init {
        connection.autoCommit = false
    }

    override fun <T> transaction(block: (StoreTransaction) -> T): T =
        synchronized(lock) {
            try {
                val result = block(Transaction(connection))
                connection.commit()
                result
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }

    override fun isReady(): Boolean =
        try {
            transaction { tx -> (tx as Transaction).ping() }
        } catch (_: java.sql.SQLException) {
            false
        }

    private class Transaction(private val connection: Connection) : StoreTransaction {
        fun ping(): Boolean = query("SELECT 1") { it.next() }

        override fun circleExists(circle: CircleId): Boolean =
            query("SELECT 1 FROM circles WHERE id = ?", { it.setString(1, circle.v) }) { rows -> rows.next() }

        override fun device(id: DeviceId): DeviceRecord? =
            query("SELECT device_id, circle_id, member_id, sign_pub, box_pub, added_seq, revoked_seq FROM devices WHERE device_id = ?", { it.setString(1, id.v) }) { rows ->
                if (!rows.next()) return@query null
                val revoked = rows.getLong(7).takeUnless { rows.wasNull() }
                DeviceRecord(
                    DeviceId(rows.getString(1)),
                    CircleId(rows.getString(2)),
                    MemberId(rows.getString(3)),
                    ByteString.of(rows.getBytes(4)),
                    ByteString.of(rows.getBytes(5)),
                    rows.getLong(6),
                    revoked,
                )
            }

        override fun memberExists(circle: CircleId, member: MemberId): Boolean =
            query("SELECT 1 FROM devices WHERE circle_id = ? AND member_id = ? LIMIT 1", {
                it.setString(1, circle.v)
                it.setString(2, member.v)
            }) { it.next() }

        override fun isSteward(circle: CircleId, member: MemberId): Boolean =
            query("SELECT 1 FROM stewards WHERE circle_id = ? AND member_id = ? AND removed_seq IS NULL", {
                it.setString(1, circle.v)
                it.setString(2, member.v)
            }) { it.next() }

        override fun currentEpoch(circle: CircleId): Int =
            query("SELECT current_epoch FROM circles WHERE id = ?", { it.setString(1, circle.v) }) { rows -> if (rows.next()) rows.getInt(1) else 0 }

        override fun invite(circle: CircleId, id: EventId): InviteRecord? =
            query("SELECT author, invite_pub, expires_day, max_uses, used FROM invites WHERE circle_id = ? AND event_id = ?", {
                it.setString(1, circle.v)
                it.setString(2, id.v)
            }) { rows ->
                if (!rows.next()) return@query null
                InviteRecord(
                    circle,
                    id,
                    MemberId(rows.getString(1)),
                    InviteTerms(ByteString.of(rows.getBytes(2)), rows.getInt(3), rows.getInt(4)),
                    rows.getInt(5),
                )
            }

        override fun findEventSeq(circle: CircleId, id: EventId): Long? =
            query("SELECT server_seq FROM events WHERE circle_id = ? AND event_id = ?", {
                it.setString(1, circle.v)
                it.setString(2, id.v)
            }) { rows -> if (rows.next()) rows.getLong(1) else null }

        override fun appendEvent(id: EventId, envelope: Envelope, receivedAtMillis: Long): Long {
            val next = query("SELECT COALESCE(MAX(server_seq), 0) + 1 FROM events WHERE circle_id = ?", { it.setString(1, envelope.circle.v) }) { rows ->
                if (rows.next()) rows.getLong(1) else FIRST_SEQ
            }
            update(
                "INSERT INTO events (server_seq, circle_id, event_id, v, author, device, type, day, ctr, epoch, public, nonce, encrypted, sig, received_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ) {
                it.setLong(1, next)
                it.setString(2, envelope.circle.v)
                it.setString(3, id.v)
                it.setInt(4, envelope.v)
                it.setString(5, envelope.author.v)
                it.setString(6, envelope.device.v)
                it.setString(7, envelope.type)
                it.setInt(8, envelope.day)
                it.setLong(9, envelope.ctr)
                it.setInt(10, envelope.epoch)
                it.setBytes(11, envelope.publicBytes())
                it.setBytes(12, envelope.nonce.toByteArray())
                it.setBytes(13, envelope.encrypted.toByteArray())
                it.setBytes(14, envelope.sig.toByteArray())
                it.setLong(15, receivedAtMillis)
            }
            return next
        }

        override fun apply(effect: Effect, serverSeq: Long) {
            when (effect) {
                is Effect.CreateCircle -> update("INSERT INTO circles (id, created_day) VALUES (?, ?)") {
                    it.setString(1, effect.circle.v)
                    it.setInt(2, effect.day)
                }
                is Effect.RegisterDevice -> registerDevice(effect.circle, effect.registration, serverSeq)
                is Effect.AddSteward -> update("INSERT INTO stewards (circle_id, member_id, added_seq) VALUES (?, ?, ?)") {
                    it.setString(1, effect.circle.v)
                    it.setString(2, effect.member.v)
                    it.setLong(3, serverSeq)
                }
                is Effect.RecordInvite -> update("INSERT INTO invites (circle_id, event_id, author, invite_pub, expires_day, max_uses) VALUES (?, ?, ?, ?, ?, ?)") {
                    it.setString(1, effect.circle.v)
                    it.setString(2, effect.id.v)
                    it.setString(3, effect.author.v)
                    it.setBytes(4, effect.terms.invitePub.toByteArray())
                    it.setInt(5, effect.terms.expiresDay)
                    it.setInt(6, effect.terms.maxUses)
                }
                is Effect.SetEpoch -> update("UPDATE circles SET current_epoch = ? WHERE id = ?") {
                    it.setInt(1, effect.epoch)
                    it.setString(2, effect.circle.v)
                }
                is Effect.UseInvite -> update("UPDATE invites SET used = used + 1 WHERE circle_id = ? AND event_id = ?") {
                    it.setString(1, effect.circle.v)
                    it.setString(2, effect.id.v)
                }
            }
        }

        private fun registerDevice(circle: CircleId, registration: DeviceRegistration, serverSeq: Long) {
            update("INSERT INTO devices (device_id, circle_id, member_id, sign_pub, box_pub, added_seq) VALUES (?, ?, ?, ?, ?, ?)") {
                it.setString(1, registration.device.v)
                it.setString(2, circle.v)
                it.setString(3, registration.member.v)
                it.setBytes(4, registration.signPub.toByteArray())
                it.setBytes(5, registration.boxPub.toByteArray())
                it.setLong(6, serverSeq)
            }
        }

        override fun eventsAfter(circle: CircleId, after: Long, limit: Int): List<StoredEvent> =
            query(
                "SELECT server_seq, v, author, device, type, day, ctr, epoch, public, nonce, encrypted, sig, event_id " +
                    "FROM events WHERE circle_id = ? AND server_seq > ? ORDER BY server_seq LIMIT ?",
                {
                    it.setString(1, circle.v)
                    it.setLong(2, after)
                    it.setInt(3, limit)
                },
            ) { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            StoredEvent(
                                rows.getLong(1),
                                EnvelopeDto(
                                    v = rows.getInt(2),
                                    circle = circle.v,
                                    author = rows.getString(3),
                                    device = rows.getString(4),
                                    type = rows.getString(5),
                                    day = rows.getInt(6),
                                    ctr = rows.getLong(7),
                                    epoch = rows.getInt(8),
                                    public = ByteString.of(rows.getBytes(9)).toBase64Url(),
                                    nonce = ByteString.of(rows.getBytes(10)).toBase64Url(),
                                    encrypted = ByteString.of(rows.getBytes(11)).toBase64Url(),
                                    sig = ByteString.of(rows.getBytes(12)).toBase64Url(),
                                ),
                            ),
                        )
                    }
                }
            }

        override fun putChallenge(nonce: ByteString, device: DeviceId, expiresAtMillis: Long) {
            update("INSERT INTO challenges (nonce, device_id, expires_at) VALUES (?, ?, ?)") {
                it.setBytes(1, nonce.toByteArray())
                it.setString(2, device.v)
                it.setLong(3, expiresAtMillis)
            }
        }

        override fun takeChallenge(nonce: ByteString): ChallengeRecord? {
            val found = query("SELECT device_id, expires_at FROM challenges WHERE nonce = ?", { it.setBytes(1, nonce.toByteArray()) }) { rows ->
                if (rows.next()) ChallengeRecord(DeviceId(rows.getString(1)), rows.getLong(2)) else null
            }
            if (found != null) update("DELETE FROM challenges WHERE nonce = ?") { it.setBytes(1, nonce.toByteArray()) }
            return found
        }

        override fun putSession(tokenHash: ByteString, device: DeviceId, expiresAtMillis: Long) {
            update("INSERT INTO sessions (token_hash, device_id, expires_at) VALUES (?, ?, ?)") {
                it.setBytes(1, tokenHash.toByteArray())
                it.setString(2, device.v)
                it.setLong(3, expiresAtMillis)
            }
        }

        override fun findSession(tokenHash: ByteString): SessionRecord? =
            query("SELECT device_id, expires_at FROM sessions WHERE token_hash = ?", { it.setBytes(1, tokenHash.toByteArray()) }) { rows ->
                if (rows.next()) SessionRecord(DeviceId(rows.getString(1)), rows.getLong(2)) else null
            }

        override fun deleteExpired(nowMillis: Long) {
            update("DELETE FROM challenges WHERE expires_at <= ?") { it.setLong(1, nowMillis) }
            update("DELETE FROM sessions WHERE expires_at <= ?") { it.setLong(1, nowMillis) }
        }

        private fun <T> query(sql: String, bind: (PreparedStatement) -> Unit = {}, read: (ResultSet) -> T): T =
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeQuery().use(read)
            }

        private fun update(sql: String, bind: (PreparedStatement) -> Unit = {}) {
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeUpdate()
            }
        }
    }
}
