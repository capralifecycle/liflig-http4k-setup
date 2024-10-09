package no.liflig.http4k.setup

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.liflig.http4k.setup.filters.RequestIdMdcFilter
import no.liflig.http4k.setup.logging.LoggedBody
import no.liflig.http4k.setup.logging.LoggingFilter
import no.liflig.http4k.setup.logging.PrincipalLog
import no.liflig.http4k.setup.logging.RequestLog
import no.liflig.http4k.setup.logging.RequestResponseLog
import no.liflig.http4k.setup.logging.ResponseLog
import no.liflig.http4k.setup.normalization.NormalizedStatusCode
import no.liflig.http4k.setup.testutils.useHttpServer
import org.http4k.core.Body
import org.http4k.core.ContentType
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.filter.ServerFilters
import org.http4k.lens.RequestContextKey
import org.http4k.lens.string
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
    val requestIdChainLens = RequestContextKey.required<List<UUID>>(contexts)
    val logs: MutableList<RequestResponseLog<CustomPrincipalLog>> = mutableListOf()

    val loggingFilter =
        LoggingFilter(
            principalLog = { CustomPrincipalLog },
            errorLogLens = RequestContextKey.optional(contexts),
            normalizedStatusLens = RequestContextKey.optional(contexts),
            requestIdChainLens = requestIdChainLens,
            logHandler = { log -> logs.add(log) },
        )

    val request = Request(Method.GET, "/some/url")

    val handler =
        ServerFilters.InitialiseRequestContext(contexts)
            .then(RequestIdMdcFilter(requestIdChainLens))
            .then(loggingFilter)
            .then { Response(Status.OK).body("hello world") }

    val response = handler(request)

    response.status shouldBe Status.OK

    logs shouldHaveSize 1
    val log = logs.first()
    log.principal shouldBe CustomPrincipalLog
    log.request.body shouldBe LoggedBody.raw("")
    log.request.method shouldBe "GET"
    log.request.size shouldBe 0
    log.request.uri shouldBe "/some/url"
    log.response.body shouldBe LoggedBody.raw("hello world")
    log.response.size shouldBe 11
    log.response.statusCode shouldBe 200
    log.status?.code shouldBe NormalizedStatusCode.OK
  }

  @Test
  fun `filter will redact authorization header by default`() {
    val logs: MutableList<RequestResponseLog<CustomPrincipalLog>> = mutableListOf()
    val requestIdChainLens = RequestContextKey.required<List<UUID>>(contexts)

    val loggingFilter =
        LoggingFilter(
            principalLog = { CustomPrincipalLog },
            errorLogLens = RequestContextKey.optional(contexts),
            normalizedStatusLens = RequestContextKey.optional(contexts),
            requestIdChainLens = requestIdChainLens,
            logHandler = { log -> logs.add(log) },
        )

    val request = Request(Method.GET, "/some/url").header("authorization", "my very secret value")

    val handler =
        ServerFilters.InitialiseRequestContext(contexts)
            .then(RequestIdMdcFilter(requestIdChainLens))
            .then(loggingFilter)
            .then { Response(Status.OK).body("hello world") }

    handler(request)

    logs shouldHaveSize 1
    val log = logs.first()
    val authorizationHeaders =
        log.request.headers.filter { it["name"].equals("authorization", true) }
    authorizationHeaders shouldHaveSize 1
    authorizationHeaders.first()["value"] shouldBe "*REDACTED*"
  }

  @Test
  fun `excludeResponseBodyFromLog excludes response body`() {
    val requestIdChainLens = RequestContextKey.required<List<UUID>>(contexts)
    val logs: MutableList<RequestResponseLog<CustomPrincipalLog>> = mutableListOf()

    val loggingFilter =
        LoggingFilter(
            includeBody = true,
            principalLog = { CustomPrincipalLog },
            errorLogLens = RequestContextKey.optional(contexts),
            normalizedStatusLens = RequestContextKey.optional(contexts),
            requestIdChainLens = requestIdChainLens,
            logHandler = { log -> logs.add(log) },
        )

    val request = Request(Method.GET, "/some/url").body("request body")

    val handler =
        ServerFilters.InitialiseRequestContext(contexts)
            .then(RequestIdMdcFilter(requestIdChainLens))
            .then(loggingFilter)
            .then { receivedRequest ->
              receivedRequest.excludeRequestBodyFromLog()
              receivedRequest.excludeResponseBodyFromLog()
              Response(Status.OK).body("hello world")
            }

    val response = handler(request)

    response.status shouldBe Status.OK

    logs shouldHaveSize 1
    val log = logs.first()
    log.request.body shouldBe null
    log.response.body shouldBe null
  }

  @Test
  fun `errorResponse includes exception in log`() {
    val requestIdChainLens = RequestContextKey.required<List<UUID>>(contexts)
    val logs: MutableList<RequestResponseLog<CustomPrincipalLog>> = mutableListOf()

    val loggingFilter =
        LoggingFilter(
            includeBody = true,
            principalLog = { CustomPrincipalLog },
            /**
             * Use global [errorLogLens], which is what is used by [errorResponse] and
             * [no.liflig.http4k.setup.LifligBasicApiSetup.create].
             */
            errorLogLens = errorLogLens,
            normalizedStatusLens = RequestContextKey.optional(contexts),
            requestIdChainLens = requestIdChainLens,
            logHandler = { log -> logs.add(log) },
        )

    val exception = Exception("test exception")

    val handler =
        ServerFilters.InitialiseRequestContext(contexts)
            .then(RequestIdMdcFilter(requestIdChainLens))
            .then(loggingFilter)
            .then { request ->
              errorResponse(request, Status.NOT_FOUND, "Not found", cause = exception)
            }

    val response = handler(Request(Method.GET, "/some/url").body("request body"))

    response.status shouldBe Status.NOT_FOUND

    logs shouldHaveSize 1
    val log = logs.first()
    log.throwable shouldBe exception
  }

  private val jsonBodyLens = Body.string(ContentType.APPLICATION_JSON).toLens()

  /**
   * We previously had a bug where request bodies would not be logged, even though all the tests
   * here passed. The reason for this is that we only test http4k here, calling our handlers with
   * in-memory bodies, whereas in production we use an actual HTTP server (Jetty), with actual byte
   * stream bodies, which must be handled differently in some cases. So we now set up a Jetty server
   * here to test real HTTP body handling.
   */
  @Test
  fun `filter works with actual HTTP server`() {
    val logs: MutableList<RequestResponseLog<LifligUserPrincipalLog>> = mutableListOf()

    useHttpServer(
        httpHandler = { request ->
          // We previously had a bug where request bodies would not be logged if they were read in
          // the handler. So we test this by applying the body lens here.
          jsonBodyLens(request)
          Response(Status.OK).with(jsonBodyLens.of("""{"response":true}"""))
        },
        logHandler = { log -> logs.add(log) },
    ) { (httpClient, baseUrl) ->
      httpClient(
          Request(Method.POST, baseUrl).with(jsonBodyLens.of("""{"request":true}""")),
      )
    }

    logs shouldHaveSize 1
    val log = logs.first()
    log.request.body?.content shouldBe JsonObject(mapOf("request" to JsonPrimitive(true)))
    log.response.body?.content shouldBe JsonObject(mapOf("response" to JsonPrimitive(true)))
  }

  private val plainTextBodyLens = Body.string(ContentType.TEXT_PLAIN).toLens()

  @Test
  fun `readLimitedBody caps request and response body size`() {
    val logs: MutableList<RequestResponseLog<LifligUserPrincipalLog>> = mutableListOf()

    val bodyExceedingMaxLoggedSize = "A".repeat(LoggingFilter.MAX_LOGGED_BODY_SIZE + 100)

    useHttpServer(
        httpHandler = {
          Response(Status.OK).with(plainTextBodyLens.of(bodyExceedingMaxLoggedSize))
        },
        logHandler = { log -> logs.add(log) },
    ) { (httpClient, baseUrl) ->
      httpClient(
          Request(Method.POST, baseUrl).with(plainTextBodyLens.of(bodyExceedingMaxLoggedSize)),
      )
    }

    logs shouldHaveSize 1
    val log = logs.first()
    log.request.body shouldBe LoggingFilter.BODY_TOO_LONG_MESSAGE
    log.response.body shouldBe LoggingFilter.BODY_TOO_LONG_MESSAGE
  }

  @Test
  @Disabled("Until fixed")
  fun `log handler properly logs as json in combination with logback setup`() {
    val handler = LoggingFilter.createLogHandler(CustomPrincipalLog.serializer())

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
    val handler = LoggingFilter.createLogHandler(CustomPrincipalLog.serializer())

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
