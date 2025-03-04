@file:Suppress("unused")

package no.liflig.http4k.setup.filters

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
