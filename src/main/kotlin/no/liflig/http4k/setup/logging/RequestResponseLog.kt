@file:UseSerializers(
    InstantSerializer::class,
    NormalizedStatusSerializer::class,
    ThrowableSerializer::class,
    UUIDSerializer::class,
)

package no.liflig.http4k.setup.logging

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import no.liflig.http4k.setup.logging.json.InstantSerializer
import no.liflig.http4k.setup.logging.json.ThrowableSerializer
import no.liflig.http4k.setup.logging.json.UUIDSerializer
import no.liflig.http4k.setup.normalization.NormalizedStatus
import no.liflig.http4k.setup.normalization.NormalizedStatusSerializer

/**
 * Represents a "principal" (user, or requesting entity) that will be logged.
 *
 * This special logging view of a principal should exclude identifiable information like names,
 * email, etc.
 */
interface PrincipalLog

@Serializable
data class RequestResponseLog<T : PrincipalLog>(
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
    val principal: T?,
    /** Request duration in ms. */
    val durationMs: Long,
    /** Throwable during handling of request/response. */
    val throwable: Throwable?,
    val status: NormalizedStatus?,
    /** Name of the [java.lang.Thread] handling the request. */
    val thread: String,
)

@Serializable
data class RequestLog(
    /** Timestamp when we first saw the request. */
    val timestamp: Instant,
    val method: String,
    val uri: String,
    val headers: List<Map<String, String?>>,
    val size: Long?,
    val body: BodyLog?,
)

@Serializable
data class ResponseLog(
    /** Timestamp when we last saw the response. */
    val timestamp: Instant,
    val statusCode: Int,
    val headers: List<Map<String, String?>>,
    val size: Long?,
    val body: BodyLog?,
)

/**
 * [LoggingFilter] attaches the [RequestResponseLog] to the log as JSON. If the request/response
 * body is also JSON, but included as a String on [RequestResponseLog], then it will be escaped in
 * log output (i.e. '\' added before every string quote). This prevents us from using log analysis
 * tools (such as CloudWatch) to query on fields in the body.
 */
@Serializable
@JvmInline
value class BodyLog(val content: JsonElement) {
  override fun toString() = content.toString()

  internal companion object {
    internal fun raw(content: String) = BodyLog(JsonPrimitive(content))
  }
}
