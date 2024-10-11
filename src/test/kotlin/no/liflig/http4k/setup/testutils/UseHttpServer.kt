package no.liflig.http4k.setup.testutils

import no.liflig.http4k.setup.LifligBasicApiSetup
import no.liflig.http4k.setup.LifligUserPrincipalLog
import no.liflig.http4k.setup.logging.LoggingFilter
import no.liflig.http4k.setup.logging.RequestResponseLog
import org.http4k.client.JavaHttpClient
import org.http4k.core.HttpHandler
import org.http4k.core.then
import org.http4k.server.Jetty
import org.http4k.server.asServer

inline fun <ReturnT> useHttpServer(
    noinline httpHandler: HttpHandler,
    noinline logHandler: (RequestResponseLog<LifligUserPrincipalLog>) -> Unit =
        LoggingFilter.createLogHandler(),
    logHttpBody: Boolean = true,
    port: Int = 8080,
    block: (ServerTestUtils) -> ReturnT
): ReturnT {
  val (coreFilters, _) =
      LifligBasicApiSetup(
              logHandler = logHandler,
              logHttpBody = logHttpBody,
          )
          .create(principalLog = { null })

  val router = coreFilters.then(httpHandler)

  val server = router.asServer(Jetty(port))
  server.use {
    server.start()

    val testUtils =
        ServerTestUtils(
            httpClient = JavaHttpClient(),
            baseUrl = "http://localhost:${port}",
        )

    return block(testUtils)
  }
}

data class ServerTestUtils(
    val httpClient: HttpHandler,
    val baseUrl: String,
)
