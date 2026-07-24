@file:Suppress("unused")

package no.liflig.http4k.setup.filters

import io.opentelemetry.api.common.AttributeKey
import no.liflig.http4k.setup.errorhandling.CatchUnhandledThrowablesFilter
import org.http4k.core.Filter
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.filter.OpenTelemetryMetrics
import org.http4k.filter.OpenTelemetryTracing
import org.http4k.filter.ServerFilters
import org.http4k.filter.ServerFilters.CatchLensFailure

/**
 * Adds OpenTelemetry metrics, request counter and call tracing.
 *
 * You can inspect these values in CloudWatch or X-Ray with the appropriate OpenTelemetry Collector
 * set up as a sidecar container in CDK/ECS to this service.
 *
 * Must be placed after [CatchUnhandledThrowablesFilter] and before [CatchLensFailure]-filter.
 */
fun ServerFilters.http4kOpenTelemetryFilter(): Filter =
    ServerFilters.OpenTelemetryMetrics.RequestCounter()
        .then(ServerFilters.OpenTelemetryMetrics.RequestTimer())
        .then(ServerFilters.OpenTelemetryTracing())

/**
 * Adds OpenTelemetry metrics, request counter and call tracing.
 *
 * You can inspect these values in CloudWatch or X-Ray with the appropriate OpenTelemetry Collector
 * set up as a sidecar container in CDK/ECS to this service.
 */
fun ClientFilters.http4kOpenTelemetryFilter(): Filter =
    ClientFilters.OpenTelemetryMetrics.RequestCounter()
        .then(ClientFilters.OpenTelemetryMetrics.RequestTimer())
        .then(ClientFilters.OpenTelemetryTracing())

/**
 * Adds OpenTelemetry metrics, request counter and call tracing.
 *
 * Takes the OTEL `service.name` of the downstream server and attaches it to the created spans
 * According to semantic conventions. This ensures that X-Ray can link these spans to the downstream
 * service name, and prevents X-Ray from creating inferred nodes for some unknown service.
 *
 * It also creates span names with lower cardinality, according to OTEL recommendations.
 *
 * You can inspect these values in CloudWatch or X-Ray with the appropriate OpenTelemetry Collector
 * set up as a sidecar container in CDK/ECS to this service.
 */
fun ClientFilters.http4kOpenTelemetryFilterForService(peerServiceName: String): Filter =
    ClientFilters.OpenTelemetryMetrics.RequestCounter()
        .then(ClientFilters.OpenTelemetryMetrics.RequestTimer())
        .then(
            ClientFilters.OpenTelemetryTracing(
                spanNamer = { "${it.method.name} ${it.uri.host}" },
                spanCreationMutator = {
                  it.setAttribute(AttributeKey.stringKey("peer.service"), peerServiceName)
                      .setAttribute(AttributeKey.stringKey("service.peer.name"), peerServiceName)
                },
            )
        )
