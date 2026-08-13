package no.liflig.http4k.setup.filters

import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.util.Base64
import no.liflig.http4k.setup.ClientLog
import no.liflig.http4k.setup.attachClientLog
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.filter.ServerFilters
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class OpenTelemetryFiltersTest {
  // Spelled out rather than referencing the filter's constants, on purpose: these names are a wire
  // contract that dashboards and queries depend on, so a rename must fail this test, not follow
  // along silently.
  private val userIdAttribute = AttributeKey.stringKey("enduser.pseudo.id")
  private val clientIdAttribute = AttributeKey.stringKey("no.liflig.oauth.client_id")

  /**
   * These tests call `attachClientLog` without [RequestHeaderMdcFilter] in the chain, so nothing
   * clears the MDC key it writes. That is exactly the leak the filter exists to prevent, so we do
   * its job here rather than let the key follow this thread into the next test.
   */
  @AfterEach
  fun clearMdc() {
    MDC.clear()
  }

  @Test
  fun `adds end user ID from the header to the server span`() {
    val exporter = InMemorySpanExporter.create()

    val handler =
        ServerFilters.http4kOpenTelemetryFilter(openTelemetry = openTelemetryFor(exporter)).then {
          Response(Status.OK)
        }

    val request =
        Request(Method.GET, "/some/url").header("X-User-ID", "f5219811-cd2c-4883-9c3b-b51b0d3cdefa")

    handler(request).status shouldBe Status.OK

    exporter.finishedSpanItems.single().attributes.get(userIdAttribute) shouldBe
        "f5219811-cd2c-4883-9c3b-b51b0d3cdefa"
  }

  @Test
  fun `adds an attached client log to the server span`() {
    val exporter = InMemorySpanExporter.create()

    val handler =
        ServerFilters.http4kOpenTelemetryFilter(openTelemetry = openTelemetryFor(exporter)).then {
            request ->
          request.attachClientLog(ClientLog(clientId = "my-client-id"))
          Response(Status.OK)
        }

    handler(Request(Method.GET, "/some/url")).status shouldBe Status.OK

    exporter.finishedSpanItems.single().attributes.get(clientIdAttribute) shouldBe "my-client-id"
  }

  @Test
  fun `does not add identity attributes when the request carries no identity`() {
    val exporter = InMemorySpanExporter.create()

    val handler =
        ServerFilters.http4kOpenTelemetryFilter(openTelemetry = openTelemetryFor(exporter)).then {
          Response(Status.OK)
        }

    handler(Request(Method.GET, "/some/url")).status shouldBe Status.OK

    val attributes = exporter.finishedSpanItems.single().attributes.asMap()
    attributes shouldNotContainKey userIdAttribute
    attributes shouldNotContainKey clientIdAttribute
  }

  @Test
  fun `a bearer token alone leaves the client ID off the span`() {
    val exporter = InMemorySpanExporter.create()

    val handler =
        ServerFilters.http4kOpenTelemetryFilter(openTelemetry = openTelemetryFor(exporter)).then {
          Response(Status.OK)
        }

    val request =
        Request(Method.GET, "/some/url")
            .header("Authorization", "Bearer ${jwtWithPayload("""{"client_id":"my-client-id"}""")}")

    handler(request).status shouldBe Status.OK

    exporter.finishedSpanItems.single().attributes.asMap() shouldNotContainKey clientIdAttribute
  }

  private fun openTelemetryFor(exporter: InMemorySpanExporter): OpenTelemetrySdk =
      OpenTelemetrySdk.builder()
          .setTracerProvider(
              SdkTracerProvider.builder()
                  .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                  .build(),
          )
          .build()

  /** Builds an unsigned JWT-shaped token with the given payload. */
  private fun jwtWithPayload(payload: String): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
    return "$header.${encoder.encodeToString(payload.toByteArray())}.signature"
  }
}
