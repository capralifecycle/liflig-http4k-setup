package no.liflig.http4k.setup

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import java.util.Base64
import no.liflig.http4k.setup.context.RequestContextFilter
import no.liflig.http4k.setup.filters.RequestHeaderMdcFilter
import no.liflig.http4k.setup.filters.requestIdMdcChain
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.junit.jupiter.api.Test
import org.slf4j.MDC

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

  @Test
  fun `an attached client log is on the MDC in the handler and removed afterwards`() {
    var handled = false

    val handler =
        RequestHeaderMdcFilter().then { request ->
          request.attachClientLog(ClientLog(clientId = "my-client-id"))

          clientIdFromMdc() shouldBe "my-client-id"
          handled = true
          Response(Status.OK)
        }

    handler(Request(Method.GET, "/some/url"))
    handled shouldBe true

    clientIdFromMdc() shouldBe null
  }

  @Test
  fun `a JWT in the Authorization header does not put a client ID on the MDC`() {
    var handled = false

    val handler =
        RequestHeaderMdcFilter().then {
          clientIdFromMdc() shouldBe null
          handled = true
          Response(Status.OK)
        }

    val request =
        Request(Method.GET, "/some/url")
            .header("Authorization", "Bearer ${jwtWithPayload("""{"client_id":"my-client-id"}""")}")
    handler(request)
    handled shouldBe true
  }

  @Test
  fun `user ID key is present with a null value when the header is absent`() {
    var handled = false

    val handler =
        RequestHeaderMdcFilter().then {
          val mdc = MDC.getCopyOfContextMap().orEmpty()
          mdc.keys shouldContain RequestHeaderMdcFilter.REQUEST_ID_MDC_KEY
          mdc.keys shouldContain RequestHeaderMdcFilter.USER_ID_MDC_KEY
          mdc[RequestHeaderMdcFilter.USER_ID_MDC_KEY] shouldBe null
          // The client ID is only put by attachClientLog, so it is absent, not null
          mdc.keys shouldNotContain RequestHeaderMdcFilter.CLIENT_ID_MDC_KEY
          handled = true
          Response(Status.OK)
        }

    handler(Request(Method.GET, "/some/url"))
    handled shouldBe true
  }

  @Test
  fun `an attached client log can be read back off the request`() {
    var handled = false

    val handler =
        RequestContextFilter().then(RequestHeaderMdcFilter()).then { request ->
          request.getClientLog() shouldBe null

          request.attachClientLog(ClientLog(clientId = "my-client-id"))
          request.getClientLog() shouldBe ClientLog(clientId = "my-client-id")

          handled = true
          Response(Status.OK)
        }

    handler(Request(Method.GET, "/some/url"))
    handled shouldBe true
  }

  private fun clientIdFromMdc(): String? = MDC.get(RequestHeaderMdcFilter.CLIENT_ID_MDC_KEY)

  /** Builds an unsigned JWT-shaped token with the given JSON payload. */
  private fun jwtWithPayload(payload: String): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
    return "$header.${encoder.encodeToString(payload.toByteArray())}.signature"
  }
}
