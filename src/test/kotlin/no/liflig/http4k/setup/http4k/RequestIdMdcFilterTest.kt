package no.liflig.http4k.setup.http4k

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import java.util.UUID
import no.liflig.http4k.setup.filters.RequestIdMdcFilter
import no.liflig.http4k.setup.filters.RequestIdMdcFilter.getRequestIdChainFromMdc
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.RequestContexts
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.filter.ServerFilters
import org.http4k.lens.RequestContextKey
import org.junit.jupiter.api.Test

class RequestIdMdcFilterTest {

  @Test
  fun `adds specific response header`() {
    val contexts = RequestContexts()
    val requestIdChainLens = RequestContextKey.required<List<UUID>>(contexts)

    val request = Request(Method.GET, "/some/url")

    val handler =
        ServerFilters.InitialiseRequestContext(contexts)
            .then(RequestIdMdcFilter(requestIdChainLens))
            .then { Response(Status.OK) }

    val response = handler(request)

    response.status shouldBe Status.OK
    response.header("x-request-id") shouldHaveLength 36
  }

  @Test
  fun `is available on MDC in the handler and be removed afterwards`() {
    val contexts = RequestContexts()
    val requestIdChainLens = RequestContextKey.required<List<UUID>>(contexts)

    val request = Request(Method.GET, "/some/url")

    var handled = false

    getRequestIdChainFromMdc() shouldBe null

    val handler =
        ServerFilters.InitialiseRequestContext(contexts)
            .then(RequestIdMdcFilter(requestIdChainLens))
            .then {
              getRequestIdChainFromMdc() shouldHaveLength 36
              handled = true
              Response(Status.OK)
            }

    handler(request)
    handled shouldBe true

    getRequestIdChainFromMdc() shouldBe null
  }
}
