package no.liflig.http4k.setup.errorhandling

import io.kotest.assertions.asClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import no.liflig.http4k.setup.LifligUserPrincipalLog
import no.liflig.http4k.setup.logging.RequestResponseLog
import no.liflig.http4k.setup.testutils.useHttpServer
import no.liflig.logging.LogLevel
import no.liflig.logging.field
import no.liflig.logging.getLogger
import no.liflig.publicexception.ErrorCode
import no.liflig.publicexception.PublicException
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.lens.LensFailure
import org.junit.jupiter.api.Test

class PublicExceptionTest {
  @Test
  fun `PublicExceptionFilter catches and maps PublicException`() {
    val handler =
        PublicExceptionFilter().then {
          throw PublicException(
              ErrorCode.BAD_REQUEST,
              publicMessage = "Test message",
              publicDetail = "Test detail",
          )
        }

    val response = handler(Request.Companion(Method.GET, "/api/test"))
    response.status shouldBe Status.Companion.BAD_REQUEST

    val responseBody = ErrorResponseBody.bodyLens(response)
    responseBody.title shouldBe "Test message"
    responseBody.detail shouldBe "Test detail"
    responseBody.status shouldBe 400
    responseBody.instance shouldBe "/api/test"
  }

  @Test
  fun `PublicExceptionFilter is included in core filter stack`() {
    val logs: MutableList<RequestResponseLog<LifligUserPrincipalLog>> = mutableListOf()

    val response =
        useHttpServer(
            httpHandler = {
              throw PublicException(
                  ErrorCode.FORBIDDEN,
                  publicMessage = "Insufficient permissions",
                  publicDetail = "This endpoint is admin-only",
              )
            },
            logHandler = { log -> logs.add(log) },
        ) { (httpClient, baseUrl) ->
          httpClient(Request(Method.GET, "${baseUrl}/api/test"))
        }

    response.status.shouldBe(Status.FORBIDDEN)

    val responseBody = ErrorResponseBody.bodyLens(response)
    responseBody.title shouldBe "Insufficient permissions"
    responseBody.detail shouldBe "This endpoint is admin-only"
    responseBody.status shouldBe 403
    responseBody.instance shouldBe "/api/test"

    logs.shouldHaveSize(1)
    logs.first().throwable.shouldBeInstanceOf<PublicException>()
  }

  @Test
  fun `PublicException as cause on LensFailure is used as response`() {
    val logs: MutableList<RequestResponseLog<LifligUserPrincipalLog>> = mutableListOf()

    val response =
        useHttpServer(
            httpHandler = { request ->
              throw LensFailure(
                  cause =
                      PublicException(
                          ErrorCode.BAD_REQUEST,
                          publicMessage = "Invalid request body",
                          publicDetail = "Missing required field 'id'",
                      ),
                  target = request,
              )
            },
            logHandler = { log -> logs.add(log) },
        ) { (httpClient, baseUrl) ->
          httpClient(Request(Method.GET, "${baseUrl}/api/test"))
        }

    val responseBody = ErrorResponseBody.bodyLens(response)
    responseBody.title shouldBe "Invalid request body"
    responseBody.detail shouldBe "Missing required field 'id'"
    responseBody.status shouldBe 400
    responseBody.instance shouldBe "/api/test"

    logs.shouldHaveSize(1)
    logs.first().throwable.shouldBeInstanceOf<PublicException>()
  }

  @Test
  fun `forwardErrorResponse correctly parses a Problem Details body`() {
    val response =
        Response(Status.BAD_REQUEST)
            .body(
                """
                  {"title":"test-title","detail":"test-detail","status":400,"instance":"/api/test"}
                """
                    .trimIndent(),
            )

    val exception =
        PublicException.forwardErrorResponse(
            response,
            source = "Test Service",
            severity = LogLevel.ERROR,
            logFields = listOf(field("key", "value")),
        )
    exception.asClue {
      it.publicMessage shouldBe "test-title"
      it.publicDetail shouldBe "test-detail"
      it.errorCode shouldBe ErrorCode.BAD_REQUEST
      it.internalDetail shouldBe "400 Bad Request response from Test Service - /api/test"
      it.severity shouldBe LogLevel.ERROR

      val logOutput = getExceptionLogOutput(it)
      logOutput.shouldContain(
          """
            "key":"value"
          """
              .trimIndent(),
      )
    }
  }

  @Test
  fun `forwardErrorResponse does not expose non-Problem Details body`() {
    val response = Response(Status.BAD_REQUEST).body("Something went wrong")

    val exception =
        PublicException.forwardErrorResponse(
            response,
            source = "Test Service",
            severity = LogLevel.ERROR,
            logFields = listOf(field("key", "value")),
        )
    exception.asClue {
      it.publicMessage shouldBe "Internal server error"
      it.publicDetail shouldBe null
      it.errorCode shouldBe ErrorCode.INTERNAL_SERVER_ERROR
      it.internalDetail shouldBe "400 Bad Request response from Test Service"
      it.severity shouldBe LogLevel.ERROR
    }
  }

  @Test
  fun `forwardErrorResponse indicates if body is empty for non-Problem Details response`() {
    val response = Response(Status.FORBIDDEN)

    val exception = PublicException.forwardErrorResponse(response, source = "Test Service")
    exception.internalDetail shouldBe "403 Forbidden response from Test Service"

    val logOutput = getExceptionLogOutput(exception)
    logOutput.shouldContain(
        """
          "errorResponseBody":""
        """
            .trimIndent(),
    )
  }

  @Test
  fun `forwardErrorResponse indicates if it failed to read body for non-Problem Details response`() {
    class AlwaysFailingBody : Body {
      override val payload: ByteBuffer
        get() = throw Exception("Body failed")

      override val length: Long? = null
      override val stream: InputStream = InputStream.nullInputStream()

      override fun close() {}
    }

    val response = Response(Status.INTERNAL_SERVER_ERROR).body(AlwaysFailingBody())

    val exception = PublicException.forwardErrorResponse(response, source = "Test Service")
    exception.internalDetail shouldBe "500 Internal Server Error response from Test Service"

    val logOutput = getExceptionLogOutput(exception)
    logOutput.shouldContain(
        """
          "errorResponseBody":null
        """
            .trimIndent(),
    )
  }
}

private val log = getLogger()

private fun getExceptionLogOutput(exception: Throwable): String {
  return captureStdout { log.error(exception) { "Test" } }
}

private fun captureStdout(block: () -> Unit): String {
  val originalStdout = System.out
  val output = ByteArrayOutputStream()

  System.setOut(PrintStream(output))
  try {
    block()
  } finally {
    System.setOut(originalStdout)
  }

  return output.toString(Charsets.UTF_8)
}
