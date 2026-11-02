package circles.server

import circles.crypto.Crypto
import circles.server.data.SqliteStore
import circles.server.domain.RateLimiter
import circles.server.domain.Unlimited
import circles.server.logging.Log
import circles.server.logging.NoLog
import circles.server.domain.AuthService
import circles.server.domain.EventRules
import circles.server.domain.SyncService
import circles.server.web.Services
import java.sql.Connection
import java.time.Clock

/** Wires the layers together over one open database connection. */
fun buildServices(
    connection: Connection,
    crypto: Crypto,
    clock: Clock,
    allowCreate: Boolean,
    log: Log = NoLog,
    rateLimit: Boolean = true,
    trustProxy: Boolean = false,
    allowPlaintextDev: Boolean = false,
): Services {
    val store = SqliteStore(connection)
    return Services(
        sync = SyncService(store, crypto, clock, EventRules(crypto, allowPlaintextDev), allowCreate, log),
        auth = AuthService(store, crypto, clock),
        store = store,
        log = log,
        limits = if (rateLimit) RateLimiter(clock) else Unlimited,
        trustProxy = trustProxy,
    )
}
