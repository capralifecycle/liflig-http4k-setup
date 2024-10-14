package no.liflig.http4k.setup

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.Serializable
import no.liflig.http4k.setup.errorhandling.ErrorResponseBody
import no.liflig.http4k.setup.logging.RequestResponseLog
import no.liflig.http4k.setup.testutils.useHttpServer
import org.http4k.core.ContentType
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.BiDiBodyLens
import org.http4k.lens.Header
import org.junit.jupiter.api.Test

class JsonBodyLensTest {
  @Serializable
  private data class ExampleDto(
      val id: Long,
      val name: String,
  ) {
    companion object {
      val bodyLens = createJsonBodyLens(serializer())
    }
  }

  @Test
  fun `lens extracts body from request`() {
    val request = Request(Method.POST, "/").body("""{"id":1,"name":"test"}""")

    val dto = ExampleDto.bodyLens(request)
    dto.id shouldBe 1
    dto.name shouldBe "test"
  }

  @Test
  fun `lens sets body on response`() {
    val dto = ExampleDto(id = 2, name = "test")
    val response = Response(Status.OK).with(ExampleDto.bodyLens.of(dto))

    response.bodyString() shouldBe """{"id":2,"name":"test"}"""
    Header.CONTENT_TYPE(response) shouldBe ContentType.APPLICATION_JSON
  }

  @Test
  fun `lens sets requestBodyIsValidJson on success`() {
    useServerRequest("""{"id":3,"name":"test"}""") { request ->
      ExampleDto.bodyLens(request)
      requestBodyIsValidJson(request) shouldBe true
    }
  }

  @Test
  fun `lens does not set requestBodyIsValidJson on failure`() {
    useServerRequest("""{"name":"no ID"}""") { request ->
      shouldThrowAny { ExampleDto.bodyLens(request) }
      requestBodyIsValidJson(request) shouldBe false
    }
  }

  @Test
  fun `custom errorResponse is returned in HTTP response`() {
    val bodyLens =
        createJsonBodyLens(
            ExampleDto.serializer(),
            errorResponse = "Failed to parse example data",
        )

    val (response, _) = getServerErrorResponse(bodyLens, path = "/example")

    response.title shouldBe "Failed to parse example data"
    response.detail shouldBe null
    response.status shouldBe 400
    response.instance shouldBe "/example"
  }

  @Test
  fun `custom errorResponseDetail is included on HTTP response`() {
    val bodyLens =
        createJsonBodyLens(
            ExampleDto.serializer(),
            errorResponseDetail = "Refer to the API specification for the correct format",
        )

    val (response, _) = getServerErrorResponse(bodyLens)

    response.title shouldBe "Failed to parse request body" // Default error response
    response.detail shouldBe "Refer to the API specification for the correct format"
  }

  @Test
  fun `includeExceptionMessageInErrorResponse includes exception message`() {
    val bodyLens =
        createJsonBodyLens(
            ExampleDto.serializer(),
            errorResponse = "Failed to parse example data",
            includeExceptionMessageInErrorResponse = true,
        )

    val (response, log) = getServerErrorResponse(bodyLens)

    // Verify that we got the JSON decoding exception we expect
    @OptIn(ExperimentalSerializationApi::class)
    log.throwable.shouldBeInstanceOf<MissingFieldException>()

    response.title shouldBe "Failed to parse example data"
    response.detail.shouldNotBeNull()
    response.detail shouldBe log.throwable.message
  }

  @Test
  fun `errorResponseDetail with includeExceptionMessageInErrorResponse creates combined detail message`() {
    val bodyLens =
        createJsonBodyLens(
            ExampleDto.serializer(),
            errorResponseDetail = "Invalid example data",
            includeExceptionMessageInErrorResponse = true,
        )

    val (response, log) = getServerErrorResponse(bodyLens)

    @OptIn(ExperimentalSerializationApi::class)
    log.throwable.shouldBeInstanceOf<MissingFieldException>()
    log.throwable.message.shouldNotBeNull()

    response.title shouldBe "Failed to parse request body" // Default error response
    response.detail shouldBe "Invalid example data (${log.throwable.message})"
  }

  /**
   * In order for the body lens to set [requestBodyIsValidJson], the request must be in the context
   * of an actual HTTP server.
   */
  private fun useServerRequest(requestBody: String, block: (Request) -> Unit) {
    useHttpServer(
        httpHandler = { request ->
          block(request)
          Response(Status.OK)
        },
    ) { (httpClient, baseUrl) ->
      val response = httpClient(Request(Method.POST, baseUrl).body(requestBody))
      withClue({ response.bodyString() }) { response.status shouldBe Status.OK }
    }
  }

  private fun getServerErrorResponse(
      bodyLens: BiDiBodyLens<*>,
      requestBody: String = """{"name":"no ID"}""",
      path: String = "",
      expectedResponseStatus: Status = Status.BAD_REQUEST,
  ): Pair<ErrorResponseBody, RequestResponseLog<*>> {
    val logs = mutableListOf<RequestResponseLog<*>>()

    useHttpServer(
        httpHandler = { request ->
          bodyLens(request)
          Response(Status.OK)
        },
        logHandler = { log -> logs.add(log) },
    ) { (httpClient, baseUrl) ->
      val response = httpClient(Request(Method.POST, baseUrl + path).body(requestBody))
      response.status shouldBe expectedResponseStatus
      logs shouldHaveSize 1
      return Pair(ErrorResponseBody.bodyLens(response), logs.first())
    }
  }
}
