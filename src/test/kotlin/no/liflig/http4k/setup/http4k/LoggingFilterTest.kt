package no.liflig.http4k.setup.http4k

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLineCount
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.liflig.http4k.setup.filters.RequestIdMdcFilter
import no.liflig.http4k.setup.logging.LoggingFilter
import no.liflig.http4k.setup.logging.PrincipalLog
import no.liflig.http4k.setup.logging.RequestLog
import no.liflig.http4k.setup.logging.RequestResponseLog
import no.liflig.http4k.setup.logging.ResponseLog
import no.liflig.http4k.setup.normalization.NormalizedStatusCode
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.RequestContexts
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.filter.ServerFilters
import org.http4k.lens.RequestContextKey
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

// Note: slf4j will only write MDC values if using specific backends.
// This test only covers logback which is set up for this project
// and assumed to be what we use with liflig-logging.

class LoggingFilterTest {

  private val exampleLog: RequestResponseLog<CustomPrincipalLog> =
      RequestResponseLog(
          timestamp = Instant.parse("2021-04-25T21:27:12.332741Z"),
          requestId = UUID.fromString("e1354392-8488-4ac0-9327-e22cd4d877ec"),
          requestIdChain = listOf(UUID.fromString("e1354392-8488-4ac0-9327-e22cd4d877ec")),
          request =
              RequestLog(
                  timestamp = Instant.parse("2021-04-25T21:27:12.222741Z"),
                  method = "GET",
                  uri = "/example",
                  headers = emptyList(),
                  size = null,
                  body = null,
              ),
          response =
              ResponseLog(
                  timestamp = Instant.parse("2021-04-25T21:27:12.302741Z"),
                  statusCode = 200,
                  headers = emptyList(),
                  size = null,
                  body = null,
              ),
          principal = null,
          durationMs = 10,
          throwable = null,
          status = null,
          thread = "dummy",
      )

  @Test
  fun `filter gives expected log object`() {
    val contexts = RequestContexts()
    val requestIdChainLens = RequestContextKey.required<List<UUID>>(contexts)
    val events: MutableList<RequestResponseLog<CustomPrincipalLog>> = mutableListOf()

    val loggingFilter =
        LoggingFilter(
            principalLog = { CustomPrincipalLog },
            errorLogLens = RequestContextKey.optional(contexts),
            normalizedStatusLens = RequestContextKey.optional(contexts),
            requestIdChainLens = requestIdChainLens,
            logHandler = {
              events.add(it)
              Unit
            },
        )

    val request = Request(Method.GET, "/some/url")

    val handler =
        ServerFilters.InitialiseRequestContext(contexts)
            .then(RequestIdMdcFilter(requestIdChainLens))
            .then(loggingFilter)
            .then { Response(Status.OK).body("hello world") }

    val response = handler(request)

    response.status shouldBe Status.OK

    events shouldHaveSize 1
    val event = events.first()

    event.principal shouldBe CustomPrincipalLog
    event.request.body shouldBe ""
    event.request.method shouldBe "GET"
    event.request.size shouldBe 0
    event.request.uri shouldBe "/some/url"
    event.response.body shouldBe "hello world"
    event.response.size shouldBe 11
    event.response.statusCode shouldBe 200
    event.status?.code shouldBe NormalizedStatusCode.OK
  }

  @Test
  fun `filter will redact authorization header by default`() {
    val contexts = RequestContexts()
    val events: MutableList<RequestResponseLog<CustomPrincipalLog>> = mutableListOf()
    val requestIdChainLens = RequestContextKey.required<List<UUID>>(contexts)

    val loggingFilter =
        LoggingFilter(
            principalLog = { CustomPrincipalLog },
            errorLogLens = RequestContextKey.optional(contexts),
            normalizedStatusLens = RequestContextKey.optional(contexts),
            requestIdChainLens = requestIdChainLens,
            logHandler = {
              events.add(it)
              Unit
            },
        )

    val request = Request(Method.GET, "/some/url").header("authorization", "my very secret value")

    val handler =
        ServerFilters.InitialiseRequestContext(contexts)
            .then(RequestIdMdcFilter(requestIdChainLens))
            .then(loggingFilter)
            .then { Response(Status.OK).body("hello world") }

    handler(request)

    events shouldHaveSize 1
    val event = events.first()

    val authorizationHeaders =
        event.request.headers.filter { it["name"].equals("authorization", true) }
    authorizationHeaders shouldHaveSize 1
    authorizationHeaders.first()["value"] shouldBe "*REDACTED*"
  }

  @Test
  @Disabled("Until fixed")
  fun `log handler properly logs as json in combination with logback setup`() {
    val handler = LoggingFilter.createLogHandler(false, CustomPrincipalLog.serializer())

    val result = captureStdout { handler(exampleLog) }

    // Line count = 2 due to trailing newline
    result shouldHaveLineCount 2

    // We expect stdout to be the JSON log line from the
    // logback configuration for tests in this repo.
    val json = Json.parseToJsonElement(result)

    json.jsonObject["requestInfo"]!!
        .jsonObject["request"]!!
        .jsonObject["uri"]!!
        .jsonPrimitive
        .contentOrNull shouldBe "/example"
  }

  @Test
  @Disabled("Until fixed")
  fun `log handler uses expected logger`() {
    val handler = LoggingFilter.createLogHandler(false, CustomPrincipalLog.serializer())

    val result = captureStdout { handler(exampleLog) }

    // Line count = 2 due to trailing newline
    result shouldHaveLineCount 2

    // We expect stdout to be the JSON log line from the
    // logback configuration for tests in this repo.
    val json = Json.parseToJsonElement(result)

    // Consumers of this library might have custom configuration
    // using this logger name, so we don't want to change it
    // unintentionally.
    json.jsonObject["logger_name"]!!.jsonPrimitive.contentOrNull shouldBe
        "no.liflig.logging.http4k.LoggingFilter"
  }

  private fun captureStdout(block: () -> Unit): String =
      try {
        val out = ByteArrayOutputStream()
        System.setOut(PrintStream(out))

        block()

        out.toString()
      } finally {
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out)))
      }

  @Serializable object CustomPrincipalLog : PrincipalLog
}
