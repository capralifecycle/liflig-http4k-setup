@file:UseSerializers(
    InstantSerializer::class,
    ThrowableSerializer::class,
    UUIDSerializer::class,
)

package no.liflig.http4k.setup.logging

import java.time.Instant
import java.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import no.liflig.http4k.setup.ClientLog
import no.liflig.http4k.setup.logging.json.InstantSerializer
import no.liflig.http4k.setup.logging.json.ThrowableSerializer
import no.liflig.http4k.setup.logging.json.UUIDSerializer
import no.liflig.http4k.setup.normalization.NormalizedStatus
import no.liflig.logging.LogLevel

@Serializable
data class RequestResponseLog<PrincipalLogT : PrincipalLog>(
    /** Timestamp when the log entry is created. */
    val timestamp: Instant,
    val requestId: UUID,
    /**
     * The request-ID chain contains all traced request-IDs. The last element is the newest in the
     * chain, and will always reference this request itself and have the same value as [requestId].
     */
    val requestIdChain: List<UUID>,
    /**
     * From the `X-User-ID` request header.
     *
     * Caller-supplied and unverified: anyone who can reach the API can set this header to any
     * value. Treat it as a hint, not as evidence of who called. [client] is verified, and differs
     * in that respect.
     */
    val requestUserId: String?,
    val request: RequestLog,
    val response: ResponseLog,
    /** The [Principal][PrincipalLog] that executed the request. */
    val principal: PrincipalLogT?,
    /**
     * The machine-to-machine client that called us, if the application attached one with
     * [attachClientLog][no.liflig.http4k.setup.attachClientLog].
     *
     * Unlike [requestUserId], this is only as trustworthy as the application's own token
     * verification - the value never comes from an unverified token.
     *
     * Defaults to null, unlike [principal] and the other fields here, because it was added to an
     * existing log schema: entries written before it existed have no `client` key, and
     * kotlinx.serialization requires a default for a property to be optional when reading.
     */
    val client: ClientLog? = null,
    /** Request duration in ms. */
    val durationMs: Long,
    /** Throwable during handling of request/response. */
    val throwable: Throwable?,
    val status: NormalizedStatus?,
    /** Name of the [java.lang.Thread] handling the request. */
    val thread: String,
    /**
     * Non-null if the library user set a custom log level for the request log (e.g. by passing
     * `severity` to [no.liflig.http4k.setup.errorResponse], or calling
     * [no.liflig.http4k.setup.setLogLevel]).
     *
     * We include this here in order to use it in [LoggingFilter.logEntry] (where we don't have
     * access to the original request), but mark it as [kotlinx.serialization.Transient] so it's not
     * included in the log output (since the log level is already part of the log output).
     */
    @kotlinx.serialization.Transient val logLevel: LogLevel? = null,
)

@Serializable
data class RequestLog(
    /** Timestamp when we first saw the request. */
    val timestamp: Instant,
    val method: String,
    val uri: String,
    val headers: List<Map<String, String?>>,
    val size: Long?,
    val body: HttpBodyLog?,
)

@Serializable
data class ResponseLog(
    /** Timestamp when we last saw the response. */
    val timestamp: Instant,
    val statusCode: Int,
    val headers: List<Map<String, String?>>,
    val size: Long?,
    val body: HttpBodyLog?,
)
