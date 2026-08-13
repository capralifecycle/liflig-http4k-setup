package no.liflig.http4k.setup

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.Serializable
import no.liflig.http4k.setup.context.RequestContext
import no.liflig.http4k.setup.filters.OAUTH_CLIENT_ID_ATTRIBUTE
import no.liflig.http4k.setup.filters.RequestHeaderMdcFilter
import org.http4k.core.Request
import org.slf4j.MDC

/**
 * Defines what info about a calling machine-to-machine client should be in the API request log.
 *
 * This is the counterpart to [LifligUserPrincipalLog] for callers that are applications rather than
 * end users. Like that one, it is a logging view: keep identifiable information out of it.
 */
@Serializable
data class ClientLog(
    /**
     * Typically the `client_id` claim of the caller's access token, which is REQUIRED in JWT access
     * tokens per RFC 9068 section 2.2, and which Cognito populates with the user pool app client
     * ID.
     */
    val clientId: String,
)

/**
 * Attaches info about the machine-to-machine client that made this request, for logging and
 * tracing. A single call records it in three places:
 * - `client` on the request log
 *   ([RequestResponseLog][no.liflig.http4k.setup.logging.RequestResponseLog])
 * - the `clientId` MDC key, so log lines made while handling the request carry it
 * - the `no.liflig.oauth.client_id` attribute on the current OpenTelemetry span
 *
 * **Call this only after verifying the token's signature.** This library cannot verify it for you -
 * that needs issuer and JWKS configuration, which lives in your application. Anyone can put any
 * `client_id` in an unsigned token, so an unverified value would let a caller impersonate another
 * client in your logs and traces.
 *
 * ### Example
 *
 * ```
 * class JwtAuthFilter : Filter {
 *   // ...
 *
 *   fun validateToken(request: Request) {
 *     // Only once the signature checks out do we know the client ID is really the caller's
 *     val tokenClaims = verifyToken(request)
 *
 *     request.attachClientLog(ClientLog(clientId = tokenClaims.clientId))
 *   }
 * }
 * ```
 *
 * ### Notes
 * - [RequestHeaderMdcFilter] must be in your filter stack, since its cleanup removes the MDC key
 *   after the request. Without it, a pooled worker thread carries one client's ID into the next
 *   request's log lines.
 *   [LifligBasicApiSetup.create][no.liflig.http4k.setup.LifligBasicApiSetup.create] always adds it.
 * - Log lines made before this call do not carry the client ID, and requests rejected before
 *   authentication completes carry it nowhere. The span attribute is set after the sampling
 *   decision, so OpenTelemetry samplers cannot see it.
 * - Calling this more than once for the same request overwrites the previous value everywhere. Last
 *   write wins.
 * - Calling this on a request that never passed through our filters (an outgoing client request,
 *   for example) skips the request log, but still writes the MDC key and span attribute.
 */
fun Request.attachClientLog(clientLog: ClientLog) {
  RequestContext.setClientLog(this, clientLog)
  MDC.put(RequestHeaderMdcFilter.CLIENT_ID_MDC_KEY, clientLog.clientId)
  Span.current().setAttribute(OAUTH_CLIENT_ID_ATTRIBUTE, clientLog.clientId)
}

/**
 * Returns the [ClientLog] previously attached to the request with [attachClientLog], or null if
 * none was attached.
 */
fun Request.getClientLog(): ClientLog? {
  return RequestContext.getClientLog(this)
}
