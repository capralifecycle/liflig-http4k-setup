package no.liflig.http4k.setup.errorhandling

import no.liflig.http4k.setup.logging.LoggingFilter
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.BiDiLens

/**
 * Filter to avoid leaking throwables to the client. It puts the throwable in context so that
 * [LoggingFilter] can pick it up and put it in the API request log. Then it responds with an 500
 * status code and standardized error body.
 *
 * This filter is a pics up errors has not been handled properly on a higher level e.g. in endpoint
 * logic.
 *
 * It is important to catch the throwables and not let it pass to Jetty as it will potentially be
 * displayed for the client with a Jetty-specific response.
 *
 * Note! Must be placed after [LoggingFilter] in filter chain in order to properly attach throwable
 * to log.
 */
class CatchUnhandledThrowablesFilter(
    private val errorLogLens: BiDiLens<Request, ErrorLog?>,
) : Filter {
  override fun invoke(nextHandler: HttpHandler): HttpHandler {
    return { request ->
      try {
        nextHandler(request)
      } catch (throwable: Throwable) {
        request.with(errorLogLens of ErrorLog(throwable))

        Response(Status.INTERNAL_SERVER_ERROR)
            .with(
                ErrorResponseBody.bodyLens of
                    ErrorResponseBody(
                        title = "Internal server error",
                        detail = null,
                        status = Status.INTERNAL_SERVER_ERROR.code,
                        instance = request.uri.path,
                    ),
            )
      }
    }
  }
}
