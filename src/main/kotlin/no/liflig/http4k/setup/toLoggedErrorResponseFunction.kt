package no.liflig.http4k.setup

import no.liflig.logging.ErrorLog
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.BiDiLens

/**
 * Convenience function that puts throwable in context for API request log and returns standardized
 * response body.
 */
typealias ToLoggedErrorResponseFunction =
    (
        request: Request,
        status: Status,
        title: String,
        detail: String?,
        throwable: Throwable?,
    ) -> Response

internal fun toLoggedErrorResponseFunction():
    (BiDiLens<Request, ErrorLog?>) -> ToLoggedErrorResponseFunction = { errorLogLens ->
  { request, status, title, detail, throwable ->
    throwable?.let { request.with(errorLogLens of ErrorLog(it)) }

    Response(status)
        .with(
            ErrorResponseBody.bodyLens of
                ErrorResponseBody(
                    title = title,
                    detail = detail,
                    status = status.code,
                    instance = request.uri.path))
  }
}
