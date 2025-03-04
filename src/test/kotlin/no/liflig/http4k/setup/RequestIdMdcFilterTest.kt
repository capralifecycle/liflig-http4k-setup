package no.liflig.http4k.setup

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import no.liflig.http4k.setup.filters.RequestIdMdcFilter
import no.liflig.http4k.setup.filters.getRequestIdChainFromMdc
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.junit.jupiter.api.Test

class RequestIdMdcFilterTest {
  @Test
  fun `adds specific response header`() {
    val handler = RequestIdMdcFilter().then { Response(Status.OK) }

    val request = Request(Method.GET, "/some/url")
    val response = handler(request)

    response.status shouldBe Status.OK
    response.header("x-request-id") shouldHaveLength 36
  }

  @Test
  fun `is available on MDC in the handler and be removed afterwards`() {
    var handled = false

    getRequestIdChainFromMdc() shouldBe null

    val handler =
        RequestIdMdcFilter().then {
          getRequestIdChainFromMdc() shouldHaveLength 36 // Length of UUID
          handled = true
          Response(Status.OK)
        }

    val request = Request(Method.GET, "/some/url")
    handler(request)
    handled shouldBe true

    getRequestIdChainFromMdc() shouldBe null
  }
}
