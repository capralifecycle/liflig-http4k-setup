package no.liflig.http4k.setup

import no.liflig.http4k.setup.errorhandling.ErrorLog
import no.liflig.http4k.setup.errorhandling.ErrorResponseBody
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.RequestContextKey

val errorLogLens = RequestContextKey.optional<ErrorLog>(contexts)

/** Convenience function that puts throwable in context for API request log. */
fun Request.attachThrowableToLog(throwable: Throwable) {
  with(errorLogLens of ErrorLog(throwable))
}

/**
 * Returns a standardized error response following the
 * [Problem Details](https://datatracker.ietf.org/doc/html/rfc7807) specification.
 *
 * @param cause Attaches the exception to the request log, if given. You should always set this if
 *   the error response was due to an exception.
 */
fun errorResponse(
    request: Request,
    status: Status,
    title: String,
    detail: String? = null,
    cause: Throwable? = null
): Response {
  if (cause != null) {
    request.attachThrowableToLog(cause)
  }
  return Response(status)
      .with(
          ErrorResponseBody.bodyLens of
              ErrorResponseBody(
                  title = title,
                  detail = detail,
                  status = status.code,
                  instance = request.uri.path,
              ),
      )
}
