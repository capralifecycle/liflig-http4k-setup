package no.liflig.http4k.setup

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.semconv.SemanticAttributes
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.filter.OpenTelemetryMetrics
import org.http4k.filter.OpenTelemetryTracing
import org.http4k.filter.ServerFilters

// TODO: Should be removed from library?

/**
 * Adds OpenTelemetry metrics, request counter and call tracing.
 *
 * You can inspect these values in CloudWatch or Xray with the appropriate OpenTelemetry Collector
 * set up as a sidecar container in CDK/ECS to this service.
 *
 * Note! Must be placed after [CatchAllThrowablesFilter] in filter chain.
 */
fun http4kOpenTelemetryFilters(): Filter =
    ServerFilters.OpenTelemetryMetrics.RequestCounter()
        .then(ServerFilters.OpenTelemetryMetrics.RequestTimer())
        .then(ServerFilters.OpenTelemetryTracing())
        .then { next: HttpHandler ->
          { req: Request ->
            try {
              next(req)
            } catch (t: Throwable) {
              // The default HTTP4k integration does not record the exception itself, only its
              // message.
              Span.current().recordException(t)
              throw t
            }
          }
        }

/** Uses a corrected version of [OpenTelemetryTracing]. */
fun lifligOpenTelemetryTracing(): Filter =
    ClientFilters.OpenTelemetryTracing(
        spanNamer = { it.method.name },
        spanCompletionMutator = { span, req, res ->
          span.setAttribute(SemanticAttributes.SERVER_ADDRESS, req.uri.host)
          req.uri.port?.let {
            if (it != 80 && it != 443) {
              span.setAttribute(SemanticAttributes.SERVER_PORT, it)
            }
          }
          res.body.length?.let { span.setAttribute(SemanticAttributes.HTTP_RESPONSE_BODY_SIZE, it) }
          req.body.length?.let { span.setAttribute(SemanticAttributes.HTTP_REQUEST_BODY_SIZE, it) }

          if (res.status.code >= 400) {
            span.setStatus(StatusCode.ERROR)
          }
        },
    )
