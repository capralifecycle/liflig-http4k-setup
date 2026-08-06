package no.liflig.http4k.setup

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import java.util.Base64
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
  fun `adds client ID claim from JWT to MDC and removes it afterwards`() {
    var handled = false

    val handler =
        RequestHeaderMdcFilter().then {
          clientIdFromMdc() shouldBe "my-client-id"
          handled = true
          Response(Status.OK)
        }

    val request =
        Request(Method.GET, "/some/url")
            .header("Authorization", "Bearer ${jwtWithPayload("""{"client_id":"my-client-id"}""")}")
    handler(request)
    handled shouldBe true

    clientIdFromMdc() shouldBe null
  }

  @Test
  fun `client ID is null when JWT has no client ID claim`() {
    var handled = false

    val handler =
        RequestHeaderMdcFilter().then {
          clientIdFromMdc() shouldBe null
          handled = true
          Response(Status.OK)
        }

    val request =
        Request(Method.GET, "/some/url")
            .header("Authorization", "Bearer ${jwtWithPayload("""{"sub":"some-user"}""")}")
    handler(request)
    handled shouldBe true
  }

  @Test
  fun `does not add MDC keys without a value`() {
    var handled = false

    val handler =
        RequestHeaderMdcFilter().then {
          val mdcKeys = MDC.getCopyOfContextMap().orEmpty().keys
          mdcKeys shouldContain RequestHeaderMdcFilter.REQUEST_ID_MDC_KEY
          mdcKeys shouldNotContain RequestHeaderMdcFilter.USER_ID_MDC_KEY
          mdcKeys shouldNotContain RequestHeaderMdcFilter.CLIENT_ID_MDC_KEY
          handled = true
          Response(Status.OK)
        }

    handler(Request(Method.GET, "/some/url"))
    handled shouldBe true
  }

  @Test
  fun `malformed authorization header does not fail the request`() {
    for (authorizationHeader in listOf("not-a-jwt", "Bearer not.a.jwt", "Bearer ", "")) {
      var handled = false

      val handler =
          RequestHeaderMdcFilter().then {
            clientIdFromMdc() shouldBe null
            handled = true
            Response(Status.OK)
          }

      val request = Request(Method.GET, "/some/url").header("Authorization", authorizationHeader)
      handler(request).status shouldBe Status.OK
      handled shouldBe true
    }
  }

  private fun clientIdFromMdc(): String? = MDC.get(RequestHeaderMdcFilter.CLIENT_ID_MDC_KEY)

  /** Builds an unsigned JWT-shaped token with the given JSON payload. */
  private fun jwtWithPayload(payload: String): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
    return "$header.${encoder.encodeToString(payload.toByteArray())}.signature"
  }
}
