package no.liflig.http4k.setup.errorhandling

import io.kotest.matchers.shouldBe
import org.http4k.core.ContentType
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.Header
import org.junit.jupiter.api.Test

class ErrorResponseBodyTest {

  @Test
  fun `should render as problem json`() {
    // Given
    val error =
        ErrorResponseBody(
            title = "Test Error",
            detail = "Something went wrong",
            status = Status.INTERNAL_SERVER_ERROR.code,
            instance = "/test/case/1",
            type = "/errors/tests/1",
        )

    // When
    val response = Response(Status.INTERNAL_SERVER_ERROR).with(ErrorResponseBody.bodyLens.of(error))

    // Then
    Header.CONTENT_TYPE(response) shouldBe ContentType.Text("application/problem+json")
    response.bodyString() shouldBe
        """{"title":"Test Error","detail":"Something went wrong","status":500,"instance":"/test/case/1","type":"/errors/tests/1"}"""
  }
}
