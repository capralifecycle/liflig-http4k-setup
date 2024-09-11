package no.liflig.http4k.setup.filters

import java.util.*
import java.util.regex.Pattern
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.RequestContext
import org.http4k.core.with
import org.http4k.lens.RequestContextLens
import org.slf4j.MDC

/**
 * Filter to append a request ID to MDC logging, and also add it as an response header so the client
 * can read it.
 *
 * This helps us map an application log to a specific request, so that it provides better context,
 * and also so that log statements for the same requests can be seen together.
 */
class RequestIdMdcFilter(private val requestIdChainLens: RequestContextLens<List<UUID>>) : Filter {
  override fun invoke(nextHandler: HttpHandler): HttpHandler {
    return { request ->
      // Use the ID generated by http4k as part of the request context.
      val requestContextLens = RequestContext.lensForStore(null)
      val requestId = requestContextLens(request)
      val requestIdChain = mutableListOf<UUID>()

      // Add input request-IDs to MDC. We allow sending a chain of
      // request-IDs to be able to search across subrequests and
      // follow flow.
      //
      // The chain is serialized as a comma separated list forming
      // a stack where the last element is the newest added
      // element. The first element is the initial origin.
      val inputRequestId = request.header(HEADER_NAME)
      if (inputRequestId != null && inputRequestIdPattern.matcher(inputRequestId).matches()) {
        requestIdChain += inputRequestId.split(",").map(UUID::fromString)
      }

      requestIdChain += requestId

      try {
        MDC.put(MDC_KEY, requestIdChain.joinToString(","))

        val response = nextHandler(request.with(requestIdChainLens of requestIdChain))

        // Add request ID to the response.
        response.header(HEADER_NAME, requestId.toString())
      } finally {
        MDC.remove(MDC_KEY)
      }
    }
  }

  companion object {
    internal const val MDC_KEY = "requestIdChain"
    internal const val HEADER_NAME = "x-request-id"

    // Source: https://stackoverflow.com/a/13653180
    private const val SINGLE_REQUEST_ID_PATTERN =
        "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"

    private val inputRequestIdPattern =
        Pattern.compile(
            "^$SINGLE_REQUEST_ID_PATTERN(,$SINGLE_REQUEST_ID_PATTERN)*$",
            Pattern.CASE_INSENSITIVE,
        )
  }
}

fun getRequestIdChainFromMdc(): String? = MDC.get(RequestIdMdcFilter.MDC_KEY)

/**
 * Add the request ID to a [Request] so that it can be added to the chain when logging the request
 * in the target service.
 */
fun Request.withRequestIdChain() =
    getRequestIdChainFromMdc()?.let { header(RequestIdMdcFilter.HEADER_NAME, it) } ?: this
