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

/** Convenience function that creates standardized response body. */
fun standardErrorResponse(
    request: Request,
    status: Status,
    title: String,
    detail: String?
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
 * Convenience function that puts throwable in context for API request log and returns standardized
 * response body. TODO: Improve naming
 */
fun toLoggedStandardErrorResponse(
    request: Request,
    status: Status,
    title: String,
    detail: String?,
    throwable: Throwable?
): Response {
  throwable?.run { request.attachThrowableToLog(this) }
  return standardErrorResponse(request, status, title, detail)
}
