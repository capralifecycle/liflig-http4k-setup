package no.liflig.http4k.setup

import no.liflig.http4k.setup.context.RequestContext
import no.liflig.http4k.setup.errorhandling.ErrorResponseBody
import no.liflig.logging.LogLevel
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with

/**
 * Returns a standardized error response following the
 * [Problem Details](https://datatracker.ietf.org/doc/html/rfc7807) specification.
 *
 * @param title A short, human-readable summary of the problem type.
 * @param detail A human-readable explanation specific to this occurrence of the problem.
 * @param type An optional URI reference that identifies the problem type. The specification
 *   encourages that it should link to human-readable documentation for the problem type (e.g. an
 *   HTML page).
 * @param cause Attaches the exception to the request log, if given. You should always set this if
 *   the error response was due to an exception.
 * @param severity By default, our `LoggingFilter` logs at the `ERROR` log level for 500 responses,
 *   and `INFO` for everything else. If you want to override this log level (independent of the
 *   response status code), you can pass a custom severity for the error response here.
 */
fun errorResponse(
    request: Request,
    status: Status,
    title: String,
    detail: String? = null,
    type: String? = null,
    cause: Throwable? = null,
    severity: LogLevel? = null,
): Response {
  if (cause != null) {
    request.attachThrowableToLog(cause)
  }
  if (severity != null) {
    request.setLogLevel(severity)
  }
  return Response(status)
      .with(
          ErrorResponseBody.bodyLens of
              ErrorResponseBody(
                  title = title,
                  detail = detail,
                  status = status.code,
                  instance = request.uri.path,
                  type = type,
              ),
      )
}

/** Convenience function that puts exception in request context for our `LoggingFilter`. */
fun Request.attachThrowableToLog(throwable: Throwable) {
  RequestContext.setExceptionForLog(this, throwable)
}

/**
 * By default, our `LoggingFilter` logs at the `ERROR` log level for 500 responses, and `INFO` for
 * everything else. If you want to override this log level (independent of the response status
 * code), you can call this function on the request.
 */
fun Request.setLogLevel(logLevel: LogLevel) {
  RequestContext.setRequestLogLevel(this, logLevel)
}
