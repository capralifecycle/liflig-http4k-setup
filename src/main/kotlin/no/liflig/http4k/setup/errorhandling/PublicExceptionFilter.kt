package no.liflig.http4k.setup.errorhandling

import no.liflig.http4k.setup.errorResponse
import no.liflig.logging.LogField
import no.liflig.logging.LogLevel
import no.liflig.logging.field
import no.liflig.logging.rawJsonField
import no.liflig.publicexception.ErrorCode
import no.liflig.publicexception.PublicException
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status

/**
 * http4k filter that catches [PublicException]s and maps them to error responses (see
 * [PublicException.toErrorResponse]). The exception will also be included in logs made by the
 * [LoggingFilter][no.liflig.http4k.setup.logging.LoggingFilter].
 *
 * This filter is part of the core filter stack, so if you set up your API with
 * [LifligBasicApiSetup.create][no.liflig.http4k.setup.LifligBasicApiSetup.create], then all
 * `PublicException`s thrown in the context of your HTTP handlers will be caught by this filter.
 */
class PublicExceptionFilter : Filter {
  override fun invoke(nextHandler: HttpHandler): HttpHandler {
    return { request ->
      try {
        nextHandler(request)
      } catch (e: PublicException) {
        e.toErrorResponse(request)
      }
    }
  }
}

/**
 * Maps the [PublicException] to an HTTP response, using [PublicException.errorCode] as the status
 * code.
 *
 * The response body is JSON, using the
 * [Problem Details](https://datatracker.ietf.org/doc/html/rfc7807) specification, with:
 * - The `title` field set to [PublicException.publicMessage]
 * - The `detail` field set to [PublicException.publicDetail]
 *
 * The exception will also be included in the log made by the
 * [LoggingFilter][no.liflig.http4k.setup.logging.LoggingFilter], along with
 * [PublicException.internalDetail] and [PublicException.logFields].
 */
fun PublicException.toErrorResponse(request: Request): Response {
  return errorResponse(
      request,
      status = Status.fromCode(this.errorCode.httpStatusCode) ?: Status.INTERNAL_SERVER_ERROR,
      title = this.publicMessage,
      detail = this.publicDetail,
      cause = this,
      severity = this.severity,
  )
}

/**
 * Utility for when:
 * - You've received an error response from another service in your system (that you own)
 * - That service formats error responses as [ErrorResponseBody] (following the
 *   [Problem Details](https://datatracker.ietf.org/doc/html/rfc7807) specification)
 * - You want to forward that error response as-is to the user
 *
 * You probably do _not_ want to use this if you've received an error response from a third-party
 * service, as you may not want to expose their error responses directly to your users.
 *
 * This function assumes that the response is already known to be an error response, i.e.
 * `response.status.successful` is `false`. It tries to parse the response body as an
 * [ErrorResponseBody] following the
 * [Problem Details](https://datatracker.ietf.org/doc/html/rfc7807) specification:
 * - If parsing succeeds, the [ErrorResponseBody] is translated one-to-one to a [PublicException],
 *   so the error response can be forwarded.
 * - If parsing fails, we assume that the response body may not be safe to expose. Instead, we
 *   return a [PublicException] with "Internal server error" as the [PublicException.publicMessage],
 *   and the response body included in [PublicException.logFields].
 *
 * @param response Should already be checked that `response.status.successful` is `false`.
 * @param source The API service where the response originated from (e.g. `"User Service"`).
 *   Included in [PublicException.internalDetail] to help with debugging.
 * @param severity Set this if you want to override the default log severity for the response log
 *   (see [PublicException.severity] for more on this).
 * @param logFields Set this if you want to provide additional log fields to the response log (see
 *   [PublicException.logFields] for more on this).
 */
fun PublicException.Companion.forwardErrorResponse(
    response: Response,
    source: String,
    severity: LogLevel? = null,
    logFields: List<LogField> = emptyList(),
): PublicException {
  // We don't want to throw an exception from within this function, so we wrap the whole thing
  // in a try/catch and return a more generic PublicException if we fail.
  try {
    val errorBody = ErrorResponseBody.bodyLens(response)

    return PublicException(
        ErrorCode.fromHttpStatusCode(response.status.code) ?: ErrorCode.INTERNAL_SERVER_ERROR,
        publicMessage = errorBody.title,
        publicDetail = errorBody.detail,
        internalDetail = "${response.status} response from ${source} - ${errorBody.instance}",
        severity = severity,
        logFields = logFields,
    )
  } catch (_: Exception) {
    // If we fail to parse the response body as an ErrorResponseBody, we still want to include
    // the response body in a log field for debugging. But reading the response body may fail,
    // and we don't want to throw in that case, so we wrap this in a try/catch as well.
    val responseBodyLogField =
        try {
          rawJsonField("errorResponseBody", response.bodyString(), validJson = false)
        } catch (_: Exception) {
          field("errorResponseBody", null)
        }

    return PublicException(
        ErrorCode.INTERNAL_SERVER_ERROR,
        publicMessage = "Internal server error",
        internalDetail = "${response.status} response from ${source}",
        severity = severity,
        logFields = logFields + responseBodyLogField,
    )
  }
}
