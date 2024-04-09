package no.liflig.http4k.setup

import java.util.UUID
import no.liflig.logging.ErrorLog
import no.liflig.logging.NormalizedStatus
import no.liflig.logging.RequestResponseLog
import no.liflig.logging.http4k.LoggingFilter
import no.liflig.logging.http4k.RequestIdMdcFilter
import org.http4k.core.ContentType
import org.http4k.core.Filter
import org.http4k.core.Request
import org.http4k.core.then
import org.http4k.filter.CorsPolicy
import org.http4k.filter.ServerFilters
import org.http4k.filter.ServerFilters.CatchLensFailure
import org.http4k.lens.RequestContextKey

/**
 * Encapsulates the basic AP setup for all of our services so that they handle requests similarly.
 * It contains default core filters to be used in Liflig projects that provide the following
 * functionality:
 * - Log in json-format containing metadata about request. E.g. log id, request chain id, user info,
 *   headers, exception stacktrace etc.
 * - Catching unhandled exceptions and respond in standard json-format.
 * - OpenTelemetry setup for recording exceptions.
 * - Sets Cors policy for API.
 * - Standard way of handling validation errors by lens failure (E.g. invalid request param) and
 *   respond in standard json-format.
 * - Convenience function for explicit handling of errors that helps to return error response in
 *   standard error format and logs throwable in API request log.
 *
 * Note! Ordering of filters are important. Do not mess with them unless you know what you are
 * doing.
 *
 * TODO: Remove liflig.logging.http4k dependency
 * TODO: Add last resort catch all throwables?
 * TODO: Remove "principalLogSerializer" requirement. Make optional.
 * TODO: Remove normalizedStatusLens setup?
 * TODO: Rename logRequestBody -> logBody (both req + res)
 */
class LifligBasicApiSetup(
    private val logHandler: (RequestResponseLog<LifligUserPrincipalLog>) -> Unit,
    private val corsPolicy: CorsPolicy?,
    private val logRequestBody: Boolean
) {

  /**
   * Note that contract APIs need to specifically set errorResponseRenderer in order to map lens
   * failures to desirable response, therefore it is returned here and can be utilized during API
   * setup. This is because Contract APIs adds [CatchLensFailure]-filter per router which overrides
   * the [CatchLensFailure]-filter set below in core filters. The latter is in place for
   * non-contract-APIs.
   */
  fun config(principalLog: (Request) -> LifligUserPrincipalLog?): LifligBasicApiSetupConfig {
    val requestIdChainLens = RequestContextKey.required<List<UUID>>(contexts)
    val errorLogLens = RequestContextKey.optional<ErrorLog>(contexts)
    val normalizedStatusLens = RequestContextKey.optional<NormalizedStatus>(contexts)

    val errorResponseRenderer =
        LifligErrorResponseRenderer(
            errorLogLens = errorLogLens,
            normalizedStatusLens = normalizedStatusLens,
            delegate = LifligJsonErrorResponseRenderer,
        )

    val coreFilters =
        ServerFilters.InitialiseRequestContext(contexts)
            .then(RequestIdMdcFilter(requestIdChainLens))
            .then(
                LoggingFilter(
                    principalLog = principalLog,
                    errorLogLens = errorLogLens,
                    normalizedStatusLens = normalizedStatusLens,
                    requestIdChainLens = requestIdChainLens,
                    logHandler = logHandler,
                    includeBody = logRequestBody,
                    // Tomra Connect uses Content-Type: text/plain in their tcreservation calls,
                    // so we add that here to log those request bodies
                    contentTypesToLog =
                        listOf(ContentType.APPLICATION_JSON, ContentType.TEXT_PLAIN),
                ),
            )
            .then(CatchAllThrowablesFilter(errorLogLens))
            .then(http4kOpenTelemetryFilters())
            .let { if (corsPolicy != null) it.then(ServerFilters.Cors(corsPolicy)) else it }
            .then(CatchLensFailure(errorResponseRenderer::badRequest))

    return LifligBasicApiSetupConfig(coreFilters, errorResponseRenderer)
  }
}

data class LifligBasicApiSetupConfig(
    val coreFilters: Filter,
    val errorResponseRenderer: LifligErrorResponseRenderer
)
