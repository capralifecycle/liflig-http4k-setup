package no.liflig.http4k.setup

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import kotlinx.serialization.Serializable
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.RequestContext
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.LensFailure
import org.junit.jupiter.api.Test

class CreateJsonBodyLensTest {
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
    val request = createRequest().body("""{"id":1,"name":"test"}""")

    val dto = ExampleDto.bodyLens(request)
    dto.id shouldBe 1
    dto.name shouldBe "test"
  }

  @Test
  fun `lens sets body on response`() {
    val dto = ExampleDto(id = 2, name = "test")
    val response = Response(Status.OK).with(ExampleDto.bodyLens.of(dto))

    val responseBody = response.bodyString()
    responseBody shouldBe """{"id":2,"name":"test"}"""
  }

  @Test
  fun `lens sets requestBodyIsValidJson on success`() {
    val request = createRequest().body("""{"id":3,"name":"test"}""")

    ExampleDto.bodyLens(request)
    requestBodyIsValidJson(request) shouldBe true
  }

  @Test
  fun `lens does not set requestBodyIsValidJson on failure`() {
    val request = createRequest().body("""{"name":"no ID"}""")

    shouldThrowAny { ExampleDto.bodyLens(request) }
    requestBodyIsValidJson(request) shouldBe false
  }

  @Test
  fun `mapDecodingException maps exception as expected`() {
    class CustomException(override val message: String, override val cause: Exception) :
        Exception()

    val bodyLens =
        createJsonBodyLens(
            ExampleDto.serializer(),
            mapDecodingException = { e ->
              CustomException("Failed to parse example DTO", cause = e)
            },
        )

    val request = createRequest().body("""{"name":"no ID"}""")

    val lensException = shouldThrowExactly<LensFailure> { bodyLens(request) }
    val customException = lensException.cause.shouldBeTypeOf<CustomException>()
    customException.message shouldBe "Failed to parse example DTO"
    customException.cause.shouldNotBeNull()
  }

  /**
   * When running this in a server normally, the request passes through our http4k filters, which
   * initializes a RequestContext and sets an x-http4k-context header on the request. We simulate
   * that here in order for our tests to work with requestBodyIsValidJson.
   */
  private fun createRequest(): Request {
    val request = Request(Method.POST, "/")
    val requestContext = RequestContext()
    contexts.inject(requestContext, request)
    return request.header("x-http4k-context", requestContext.id.toString())
  }
}
