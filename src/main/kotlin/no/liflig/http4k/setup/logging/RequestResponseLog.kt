@file:UseSerializers(
    InstantSerializer::class,
    ThrowableSerializer::class,
    UUIDSerializer::class,
)

package no.liflig.http4k.setup.logging

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import no.liflig.http4k.setup.logging.json.InstantSerializer
import no.liflig.http4k.setup.logging.json.ThrowableSerializer
import no.liflig.http4k.setup.logging.json.UUIDSerializer
import no.liflig.http4k.setup.normalization.NormalizedStatus
import no.liflig.logging.LogLevel

/**
 * Represents a "principal" (user, or requesting entity) that will be logged.
 *
 * This special logging view of a principal should exclude identifiable information like names,
 * email, etc.
 */
interface PrincipalLog

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
    val request: RequestLog,
    val response: ResponseLog,
    /** The [Principal][PrincipalLog] that executed the request. */
    val principal: PrincipalLogT?,
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
