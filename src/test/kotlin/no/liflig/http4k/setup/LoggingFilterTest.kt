package no.liflig.http4k.setup

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLineCount
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.time.Instant
import java.util.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.liflig.http4k.setup.context.RequestContextFilter
import no.liflig.http4k.setup.filters.RequestIdMdcFilter
import no.liflig.http4k.setup.logging.HttpBodyLog
import no.liflig.http4k.setup.logging.JsonBodyLog
import no.liflig.http4k.setup.logging.LoggingFilter
import no.liflig.http4k.setup.logging.PrincipalLog
import no.liflig.http4k.setup.logging.RequestLog
import no.liflig.http4k.setup.logging.RequestResponseLog
import no.liflig.http4k.setup.logging.ResponseLog
import no.liflig.http4k.setup.logging.StringBodyLog
import no.liflig.http4k.setup.normalization.NormalizedStatusCode
import no.liflig.http4k.setup.testutils.useHttpServer
import no.liflig.logging.LogLevel
import no.liflig.logging.RawJson
import no.liflig.logging.rawJson
import org.http4k.core.Body
import org.http4k.core.ContentType
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.BiDiBodyLens
import org.http4k.lens.string
import org.junit.jupiter.api.Test

// Note: slf4j will only write MDC values if using specific backends.
// This test only covers logback which is set up for this project
// and assumed to be what we use with liflig-logging.

@OptIn(ExperimentalSerializationApi::class) // For JsonUnquotedLiteral
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
    val logs: MutableList<RequestResponseLog<CustomPrincipalLog>> = mutableListOf()

    val loggingFilter =
        LoggingFilter(
            principalLog = { CustomPrincipalLog },
            logHandler = { log -> logs.add(log) },
            includeBody = true,
        )

    val request = Request(Method.GET, "/some/url")

    val handler =
        RequestIdMdcFilter().then(loggingFilter).then { Response(Status.OK).body("hello world") }

    val response = handler(request)

    response.status shouldBe Status.OK

    logs shouldHaveSize 1
    val log = logs.first()
    log.principal shouldBe CustomPrincipalLog
    log.request.body shouldBe StringBodyLog("")
    log.request.method shouldBe "GET"
    log.request.size shouldBe 0
    log.request.uri shouldBe "/some/url"
    log.response.body shouldBe StringBodyLog("hello world")
    log.response.size shouldBe 11
    log.response.statusCode shouldBe 200
    log.status?.code shouldBe NormalizedStatusCode.OK
  }

  @Test
  fun `filter will redact authorization header by default`() {
    val logs: MutableList<RequestResponseLog<CustomPrincipalLog>> = mutableListOf()

    val loggingFilter =
        LoggingFilter(
            principalLog = { CustomPrincipalLog },
            logHandler = { log -> logs.add(log) },
        )

    val request = Request(Method.GET, "/some/url").header("authorization", "my very secret value")

    val handler =
        RequestIdMdcFilter().then(loggingFilter).then { Response(Status.OK).body("hello world") }

    handler(request)

    logs shouldHaveSize 1
    val log = logs.first()
    val authorizationHeaders =
        log.request.headers.filter { it["name"].equals("authorization", true) }
    authorizationHeaders shouldHaveSize 1
    authorizationHeaders.first()["value"] shouldBe "*REDACTED*"
  }

  @Test
  fun `excludeRequestBodyFromLog and excludeResponseBodyFromLog exclude bodies`() {
    val logs: MutableList<RequestResponseLog<CustomPrincipalLog>> = mutableListOf()

    val loggingFilter =
        LoggingFilter(
            includeBody = true,
            principalLog = { CustomPrincipalLog },
            logHandler = { log -> logs.add(log) },
        )

    val request = Request(Method.GET, "/some/url").with(plainTextBodyLens.of("request body"))

    val handler =
        RequestContextFilter() // Must have request context to set the body exclusion flags
            .then(RequestIdMdcFilter())
            .then(loggingFilter)
            .then { receivedRequest ->
              receivedRequest.excludeRequestBodyFromLog()
              receivedRequest.excludeResponseBodyFromLog()
              Response(Status.OK).with(plainTextBodyLens.of("hello world"))
            }

    val response = handler(request)

    response.status shouldBe Status.OK

    logs shouldHaveSize 1
    val log = logs.first()
    log.request.body shouldBe HttpBodyLog.BODY_EXCLUDED_MESSAGE
    log.response.body shouldBe HttpBodyLog.BODY_EXCLUDED_MESSAGE
  }

  @Test
  fun `errorResponse includes exception in log and sets log level`() {
    val logs: MutableList<RequestResponseLog<CustomPrincipalLog>> = mutableListOf()

    val loggingFilter =
        LoggingFilter(
            includeBody = true,
            principalLog = { CustomPrincipalLog },
            logHandler = { log -> logs.add(log) },
        )

    val exception = Exception("test exception")
    val logLevel = LogLevel.WARN

    val handler =
        RequestContextFilter() // Must have request context for attaching exception
            .then(RequestIdMdcFilter())
            .then(loggingFilter)
            .then { request ->
              errorResponse(
                  request,
                  Status.NOT_FOUND,
                  "Not found",
                  cause = exception,
                  severity = logLevel,
              )
            }

    val response = handler(Request(Method.GET, "/some/url").body("request body"))

    response.status shouldBe Status.NOT_FOUND

    logs shouldHaveSize 1
    val log = logs.first()
    log.throwable shouldBe exception
    log.logLevel shouldBe logLevel
  }

  private val jsonStringBodyLens = Body.string(ContentType.APPLICATION_JSON).toLens()

  @Serializable
  data class ExampleBody(val type: String) {
    companion object {
      val bodyLens = createJsonBodyLens(serializer())
    }
  }

  /**
   * We previously had a bug where request bodies would not be logged, even though all the tests
   * here passed. The reason for this is that we only test http4k here, calling our handlers with
   * in-memory bodies, whereas in production we use an actual HTTP server (Jetty), with actual byte
   * stream bodies, which must be handled differently in some cases. So we now set up a Jetty server
   * here to test real HTTP body handling.
   */
  @Test
  fun `filter works with actual HTTP server`() {
    val log =
        getServerLog(
            requestBody = """{"type":"request"}""",
            responseBody = """{"type":"response"}""",
        )

    log.request.body.jsonBodyLog() shouldBe rawJson("""{"type":"request"}""", validJson = true)
    log.response.body.jsonBodyLog() shouldBe rawJson("""{"type":"response"}""", validJson = true)
  }

  /** See [no.liflig.http4k.setup.markBodyAsValidJson]. */
  @Test
  fun `filter reparses request JSON when it has not been parsed in handler`() {
    val log =
        getServerLog(
            // Pass invalid JSON here, to verify below that it gets reparsed and included as an
            // escaped string in the log output
            requestBody = """{"type":"invalidJSON""",
            responseBody = """{"type":"response"}""",
            parseRequestBody = false,
        )

    log.request.body.jsonBodyLog() shouldBe rawJson("""{"type":"invalidJSON""")
    log.response.body.jsonBodyLog() shouldBe rawJson("""{"type":"response"}""", validJson = true)
  }

  @Test
  fun `filter does not reparse body with wrong Content-Type when it has already been parsed as JSON`() {
    val log =
        getServerLog(
            requestBody = """{"type":"request"}""",
            responseBody = "test",
            bodyLens = plainTextBodyLens,
        )

    log.request.body.jsonBodyLog() shouldBe rawJson("""{"type":"request"}""", validJson = true)
  }

  /**
   * CloudWatch log output breaks if we send JSON with newlines, as newlines are used to separate
   * between log entries.
   */
  @Test
  fun `filter reparses JSON with newlines`() {
    // Newlines outside values
    val requestBody =
        """{
  "type": "request"
}"""
    // Newlines inside values
    val responseBody =
        """{"type":"multiline
response"}"""

    val log = getServerLog(requestBody, responseBody)

    log.request.body.jsonBodyLog() shouldBe rawJson("""{"type":"request"}""", validJson = true)
    log.response.body.jsonBodyLog() shouldBe
        rawJson("""{"type":"multiline\nresponse"}""", validJson = true)
  }

  @Test
  fun `null JSON body parses to JsonNull`() {
    val log = getServerLog(requestBody = "null", responseBody = "null", parseRequestBody = false)

    log.request.body.jsonBodyLog() shouldBe rawJson("null", validJson = true)
    log.response.body.jsonBodyLog() shouldBe rawJson("null", validJson = true)
  }

  private val plainTextBodyLens = Body.string(ContentType.TEXT_PLAIN).toLens()

  @Test
  fun `readLimitedBody caps request and response body size`() {
    val bodyExceedingMaxLoggedSize = "A".repeat(HttpBodyLog.MAX_LOGGED_BODY_SIZE * 2)

    val log =
        getServerLog(
            requestBody = bodyExceedingMaxLoggedSize,
            responseBody = bodyExceedingMaxLoggedSize,
            bodyLens = plainTextBodyLens,
            parseRequestBody = false,
        )

    val expectedBodyLog =
        StringBodyLog(
            "A".repeat(HttpBodyLog.MAX_LOGGED_BODY_SIZE) + HttpBodyLog.TRUNCATED_BODY_SUFFIX,
        )
    log.request.body shouldBe expectedBodyLog
    log.response.body shouldBe expectedBodyLog
  }

  @Test
  fun `BodyLog fromHttpMessage catches read exceptions`() {
    val alwaysFailingBody =
        object : Body {
          override val payload: ByteBuffer
            get() = throw Exception("Expected failure")

          override val stream: InputStream
            get() = throw Exception("Expected failure")

          override val length: Long?
            get() = null

          override fun close() {}
        }

    val request = Request(Method.GET, "/").body(alwaysFailingBody)
    val bodyLog = HttpBodyLog.from(request)
    bodyLog.body shouldBe HttpBodyLog.FAILED_TO_READ_BODY_MESSAGE
  }

  @Test
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
        "no.liflig.http4k.setup.logging.LoggingFilter"
  }

  private fun getServerLog(
      requestBody: String,
      responseBody: String,
      parseRequestBody: Boolean = true,
      bodyLens: BiDiBodyLens<String> = jsonStringBodyLens,
  ): RequestResponseLog<LifligUserPrincipalLog> {
    val logs: MutableList<RequestResponseLog<LifligUserPrincipalLog>> = mutableListOf()

    useHttpServer(
        logHandler = { log -> logs.add(log) },
        httpHandler =
            fun(request): Response {
              if (parseRequestBody) {
                try {
                  ExampleBody.bodyLens(request)
                } catch (e: Exception) {
                  return Response(Status.BAD_REQUEST).body(e.message!!)
                }
              }
              return Response(Status.OK).with(bodyLens.of(responseBody))
            },
    ) { (httpClient, baseUrl) ->
      val response =
          httpClient(
              Request(Method.POST, baseUrl).with(bodyLens.of(requestBody)),
          )
      withClue(response.bodyString()) { response.status shouldBe Status.OK }
    }

    logs shouldHaveSize 1
    return logs.first()
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

/** Test utility for verifying that the given [HttpBodyLog] is a [JsonBodyLog]. */
private fun HttpBodyLog?.jsonBodyLog(): RawJson {
  return this.shouldBeInstanceOf<JsonBodyLog>().body
}
