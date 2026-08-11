@file:Suppress("unused")

package no.liflig.http4k.setup.filters

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
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
 * Span attribute for the end user a request acts on behalf of, from the
 * [RequestHeaderMdcFilter.USER_ID_HEADER] header.
 *
 * OpenTelemetry semantic convention attribute, Development stability: "pseudonymous identifier of
 * an end user (...) a random value that is not directly linked or associated with the end user's
 * actual identity". Fits our opaque user UUIDs. The neighbouring `enduser.id` carries identifying
 * personal data such as a username or email, so it would overstate what we have.
 *
 * Spelled out rather than imported from `opentelemetry-semconv`: Development attributes ship only
 * in the `-alpha` incubating artifact, whose guidance says "Library instrumentation SHOULD NOT
 * depend on this", and that libraries "should make copies of the attributes to avoid possible
 * runtime errors from version conflicts". We are a library.
 *
 * Sources:
 * - https://opentelemetry.io/docs/specs/semconv/registry/attributes/enduser/
 * - https://github.com/open-telemetry/semantic-conventions-java
 */
private val ENDUSER_PSEUDO_ID_ATTRIBUTE: AttributeKey<String> =
    AttributeKey.stringKey("enduser.pseudo.id")

/**
 * Span attribute for the machine-to-machine client that called us, set by
 * [attachClientLog][no.liflig.http4k.setup.attachClientLog].
 *
 * **Not a semantic convention - we made this name up.** OpenTelemetry has no attribute for the
 * identity of a calling application: its `client.*` namespace covers the network peer
 * (`client.address`, `client.port`) only. The OpenTelemetry naming guide says to prefix such
 * attributes with a namespace you own, and to "avoid using existing OpenTelemetry semantic
 * convention namespace as a prefix" - hence the `no.liflig` reverse-domain prefix. The `client_id`
 * component is verbatim from the claim name in RFC 8693 section 4.3.
 *
 * Source: https://opentelemetry.io/docs/specs/semconv/general/naming/
 */
internal val OAUTH_CLIENT_ID_ATTRIBUTE: AttributeKey<String> =
    AttributeKey.stringKey("no.liflig.oauth.client_id")

/**
 * Adds OpenTelemetry metrics, request counter and call tracing.
 *
 * Spans are annotated with [ENDUSER_PSEUDO_ID_ATTRIBUTE] from the
 * [RequestHeaderMdcFilter.USER_ID_HEADER] header, when the request carries it. It is set at span
 * creation, so samplers can see it - the OpenTelemetry HTTP conventions require that of
 * sampling-relevant attributes. The header is read off the request, not the MDC, so this filter
 * works on its own, without [RequestHeaderMdcFilter] in front of it.
 *
 * The identity of a machine-to-machine caller goes on the span as [OAUTH_CLIENT_ID_ATTRIBUTE], but
 * this filter cannot set it: that value must be verified first, which needs configuration only your
 * application has. Call [attachClientLog][no.liflig.http4k.setup.attachClientLog] from your auth
 * filter instead. That happens after the span is created, so samplers do not see it.
 *
 * You can inspect these values in CloudWatch or X-Ray with the appropriate OpenTelemetry Collector
 * set up as a sidecar container in CDK/ECS to this service.
 *
 * Must be placed after [CatchUnhandledThrowablesFilter] and before [CatchLensFailure]-filter.
 *
 * @param openTelemetry Instance used for tracing. Defaults to the globally registered one, which is
 *   what the OpenTelemetry agent sets up. Override to capture spans in tests.
 */
fun ServerFilters.http4kOpenTelemetryFilter(
    openTelemetry: OpenTelemetry = GlobalOpenTelemetry.get(),
): Filter =
    ServerFilters.OpenTelemetryMetrics.RequestCounter()
        .then(ServerFilters.OpenTelemetryMetrics.RequestTimer())
        .then(
            ServerFilters.OpenTelemetryTracing(
                openTelemetry = openTelemetry,
                spanCreationMutator = { spanBuilder, request ->
                  request.header(RequestHeaderMdcFilter.USER_ID_HEADER)?.let { userId ->
                    spanBuilder.setAttribute(ENDUSER_PSEUDO_ID_ATTRIBUTE, userId)
                  }
                  spanBuilder
                },
            ),
        )

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
