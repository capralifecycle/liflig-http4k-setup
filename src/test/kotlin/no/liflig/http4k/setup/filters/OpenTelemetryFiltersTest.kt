package no.liflig.http4k.setup.filters

import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.filter.ServerFilters
import org.junit.jupiter.api.Test

class OpenTelemetryFiltersTest {
  // Spelled out rather than referencing the filter's constants, on purpose: these names are a wire
  // contract that dashboards and queries depend on, so a rename must fail this test, not follow
  // along silently.
  private val userIdAttribute = AttributeKey.stringKey("enduser.pseudo.id")

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
  fun `does not add the end user ID when the request carries none`() {
    val exporter = InMemorySpanExporter.create()

    val handler =
        ServerFilters.http4kOpenTelemetryFilter(openTelemetry = openTelemetryFor(exporter)).then {
          Response(Status.OK)
        }

    handler(Request(Method.GET, "/some/url")).status shouldBe Status.OK

    exporter.finishedSpanItems.single().attributes.asMap() shouldNotContainKey userIdAttribute
  }

  private fun openTelemetryFor(exporter: InMemorySpanExporter): OpenTelemetrySdk =
      OpenTelemetrySdk.builder()
          .setTracerProvider(
              SdkTracerProvider.builder()
                  .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                  .build(),
          )
          .build()
}
