package no.liflig.http4k.setup

import mu.KLogging
import no.liflig.logging.ErrorLog
import no.liflig.logging.http4k.LoggingFilter
import org.http4k.core.Filter
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
 * This filter is a last resort and should generally only be triggered if an error has not been
 * handled properly on a higher level. It is important to catch the throwables and not let it pass
 * to Jetty as it will potentially be displayed for the client with a Jetty-specific response.
 *
 * Note! Must be placed after [LoggingFilter] in filter chain.
 */
object CatchAllThrowablesFilter : KLogging() {
  operator fun invoke(errorLogLens: BiDiLens<Request, ErrorLog?>) = Filter { next ->
    { request ->
      try {
        next(request)
      } catch (throwable: Throwable) {
        request.with(errorLogLens of ErrorLog(throwable))

        Response(Status.INTERNAL_SERVER_ERROR)
            .with(
                ErrorResponseBody.bodyLens of
                    ErrorResponseBody(
                        title = "Internal server error",
                        detail = null,
                        status = Status.INTERNAL_SERVER_ERROR.code,
                        instance = request.uri.path))
      }
    }
  }
}
