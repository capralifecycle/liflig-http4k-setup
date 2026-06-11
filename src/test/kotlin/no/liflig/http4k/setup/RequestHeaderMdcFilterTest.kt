package no.liflig.http4k.setup

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import no.liflig.http4k.setup.filters.RequestHeaderMdcFilter
import no.liflig.http4k.setup.filters.requestIdMdcChain
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.junit.jupiter.api.Test

class RequestHeaderMdcFilterTest {
  @Test
  fun `adds specific response header`() {
    val handler = RequestHeaderMdcFilter().then { Response(Status.OK) }

    val request =
        Request(Method.GET, "/some/url").header("X-User-ID", "f5219811-cd2c-4883-9c3b-b51b0d3cdefa")
    val response = handler(request)

    response.status shouldBe Status.OK
    response.header("x-request-id") shouldHaveLength 36
    response.header("X-User-ID") shouldBe null
  }

  @Test
  fun `is available on MDC in the handler and be removed afterwards`() {
    var handled = false

    requestIdMdcChain() shouldBe null

    val handler =
        RequestHeaderMdcFilter().then {
          requestIdMdcChain() shouldHaveLength 36 // Length of UUID
          handled = true
          Response(Status.OK)
        }

    val request = Request(Method.GET, "/some/url")
    handler(request)
    handled shouldBe true

    requestIdMdcChain() shouldBe null
  }
}
