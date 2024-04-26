package no.liflig.http4k.setup

import no.liflig.http4k.setup.errorhandling.ErrorLog
import no.liflig.http4k.setup.errorhandling.ErrorResponseBody
import org.http4k.core.Request
import org.http4k.core.RequestContexts
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.RequestContextKey

val contexts = RequestContexts()
val errorLogLens = RequestContextKey.optional<ErrorLog>(contexts)

/** Convenience function that puts throwable in context for API request log. */
fun Request.attachThrowableToLog(throwable: Throwable) {
  with(errorLogLens of ErrorLog(throwable))
}

/**
 * Returns a standardized error response following the 'Problem Details' specification.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7807">Problem Details specification</a>
 */
fun errorResponse(
    request: Request,
    status: Status,
    title: String,
    detail: String? = null
): Response =
    Response(status)
        .with(
            ErrorResponseBody.bodyLens of
                ErrorResponseBody(
                    title = title,
                    detail = detail,
                    status = status.code,
                    instance = request.uri.path,
                ),
        )

/**
 * Returns a standardized error response following the 'Problem Details' specification, and puts the
 * given exception in the request context for logging.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7807">Problem Details specification</a>
 */
fun loggedErrorResponse(
    request: Request,
    status: Status,
    title: String,
    detail: String? = null,
    exception: Throwable? = null
): Response {
  exception?.run { request.attachThrowableToLog(this) }
  return errorResponse(request, status, title, detail)
}
