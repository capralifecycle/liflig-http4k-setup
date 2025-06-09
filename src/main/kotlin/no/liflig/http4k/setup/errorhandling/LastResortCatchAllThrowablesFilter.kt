package no.liflig.http4k.setup.errorhandling

import no.liflig.logging.getLogger
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status

/**
 * Filter to avoid leaking exceptions to the client. This does not store anything on context but
 * logs the error and returns an 500 error.
 *
 * This filter is a last resort and should generally only pick up errors that occur in filters
 * before [CatchUnhandledThrowablesFilter] has been applied, which should happen rarely.
 *
 * It is important to catch the throwables and not let it pass to Jetty as it will potentially be
 * displayed for the client with a Jetty-specific response.
 */
class LastResortCatchAllThrowablesFilter : Filter {
  override fun invoke(nextHandler: HttpHandler): HttpHandler {
    return { request ->
      try {
        nextHandler(request)
      } catch (e: Throwable) {
        log.error(e) { "Unhandled exception caught" }
        Response(Status.INTERNAL_SERVER_ERROR).body("Something went wrong")
      }
    }
  }

  companion object {
    private val log = getLogger()
  }
}
