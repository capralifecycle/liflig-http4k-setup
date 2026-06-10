@file:Suppress("unused")

package no.liflig.http4k.setup.filters

import java.util.UUID
import java.util.regex.Pattern
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.lens.RequestKey
import org.http4k.lens.RequestLens
import org.slf4j.MDC

/**
 * A filter that manages the inclusion of request-related metadata in the MDC (Mapped Diagnostic
 * Context) for logging and tracing purposes. Specifically, it adds request ID and user ID
 * information to the contextual logging framework to enable traceability across distributed
 * services.
 *
 * This filter intercepts HTTP requests and adds a chain of request IDs representing the origin and
 * flow of the request, along with any user ID headers provided. The request ID chain is serialized
 * as a comma-separated list to form a stack, while user IDs are directly added when present.
 *
 * Additionally, the filter ensures that the generated request ID is added to the response headers
 * for further traceability downstream.
 *
 * Behavior:
 * - Extracts incoming request-related metadata (e.g., "x-request-id", "X-User-ID") from headers.
 * - Validates and parses the input request-ID chain using a predefined UUID pattern.
 * - Assigns a new request ID to the current request and appends it to the chain.
 * - Appends request metadata to the MDC for contextual logging.
 * - Ensures cleanup of MDC after request processing completes.
 *
 * Usage:
 * - Typically used as part of an HTTP filter chain to support distributed tracing or debugging.
 * - Works in conjunction with other filters processing request and response handling.
 *
 * Companion object constants:
 * - `REQUEST_ID_HEADER`: Header name for the request ID.
 * - `USER_ID_HEADER`: Header name for the user ID.
 *
 * Companion object utilities:
 * - Regular expression pattern (`inputRequestIdPattern`) for validating the request ID format.
 * - `requestIdChainLens`: A request lens for injecting the request ID chain during processing.
 *
 * See also:
 * - [org.http4k.core.Filter] for more information about HTTP filters.
 */
class RequestHeaderMdcFilter : Filter {
  override fun invoke(nextHandler: HttpHandler): HttpHandler {
    return { request ->
      val requestId = UUID.randomUUID()
      val requestIdChain = mutableListOf<UUID>()

      // Request-ID chain from header param - allow sending a chain of request-IDs to be able to
      // search across subrequests and follow flow. The chain is serialized as a comma separated
      // list forming a stack where the last element is the newest added element.
      // The first element is the initial origin.
      val inputRequestId = request.header(REQUEST_ID_HEADER)
      if (inputRequestId != null && inputRequestIdPattern.matcher(inputRequestId).matches()) {
        requestIdChain += inputRequestId.split(",").map(UUID::fromString)
      }
      requestIdChain += requestId
      // User UUID from header params
      val inputUserId = request.header(USER_ID_HEADER)

      try {
        // Add keys
        MDC.put(REQUEST_ID_MDC_KEY, requestIdChain.joinToString(","))
        MDC.put(USER_ID_MDC_KEY, inputUserId)
        // Handle request
        val response = nextHandler(requestIdChainLens.inject(requestIdChain, request))
        // Add request ID to the response
        response.header(REQUEST_ID_HEADER, requestId.toString())
      } finally {
        // Remove keys
        MDC.remove(REQUEST_ID_MDC_KEY)
        MDC.remove(USER_ID_MDC_KEY)
      }
    }
  }

  companion object {
    internal const val REQUEST_ID_HEADER = "x-request-id"
    internal const val REQUEST_ID_MDC_KEY = "requestIdChain"
    internal const val USER_ID_HEADER = "X-User-ID"
    internal const val USER_ID_MDC_KEY = USER_ID_HEADER

    // Patter for requestId, based on source https://stackoverflow.com/a/13653180
    private const val SINGLE_REQUEST_ID_PATTERN =
        "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"

    private val inputRequestIdPattern =
        Pattern.compile(
            "^$SINGLE_REQUEST_ID_PATTERN(,$SINGLE_REQUEST_ID_PATTERN)*$",
            Pattern.CASE_INSENSITIVE,
        )

    internal val requestIdChainLens: RequestLens<List<UUID>> =
        RequestKey.required(
            // Add UUID to request key name, to prevent name collisions
            name = "request-id-${UUID.randomUUID()}",
        )
  }
}

fun requestIdMdcChain(): String? = MDC.get(RequestHeaderMdcFilter.REQUEST_ID_MDC_KEY)

/**
 * Add the request ID to a [Request] so that it can be added to the chain when logging the request
 * in the target service.
 */
fun Request.withRequestIdMdcChain(): Request {
  val requestIdChain = requestIdMdcChain()
  return if (requestIdChain != null) {
    this.header(RequestHeaderMdcFilter.REQUEST_ID_MDC_KEY, requestIdChain)
  } else {
    this
  }
}
