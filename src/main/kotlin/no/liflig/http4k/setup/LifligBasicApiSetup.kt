package no.liflig.http4k.setup

import no.liflig.http4k.setup.context.RequestContextFilter
import no.liflig.http4k.setup.errorhandling.CatchUnhandledThrowablesFilter
import no.liflig.http4k.setup.errorhandling.ContractLensErrorResponseRenderer
import no.liflig.http4k.setup.errorhandling.LastResortCatchAllThrowablesFilter
import no.liflig.http4k.setup.errorhandling.PublicExceptionFilter
import no.liflig.http4k.setup.errorhandling.StandardErrorResponseBodyRenderer
import no.liflig.http4k.setup.filters.RequestIdMdcFilter
import no.liflig.http4k.setup.filters.http4kOpenTelemetryFilter
import no.liflig.http4k.setup.logging.LoggingFilter
import no.liflig.http4k.setup.logging.PrincipalLog
import no.liflig.http4k.setup.logging.RequestResponseLog
import org.http4k.contract.ErrorResponseRenderer
import org.http4k.core.ContentType
import org.http4k.core.Filter
import org.http4k.core.Request
import org.http4k.core.then
import org.http4k.filter.CorsPolicy
import org.http4k.filter.ServerFilters
import org.http4k.filter.ServerFilters.CatchLensFailure

/**
 * Encapsulates the basic API setup for all of our services so that they handle requests similarly.
 * It contains default core filters to be used in Liflig projects that provide the following
 * functionality:
 * - Log in json-format containing metadata about request. E.g. log id, request chain id, user info,
 *   headers, exception stacktrace etc.
 * - Sets up default filters in a specific order so that log is enriched properly with data.
 * - Catching unhandled exceptions and respond in standard json-format.
 * - OpenTelemetry setup for recording exceptions and response status codes.
 * - Sets Cors policy for API.
 * - Standard way of handling validation errors by lens failure in contract APIs (E.g. invalid
 *   request param) and respond in standard json-format.
 * - Convenience function for explicit handling of application errors that helps to return error
 *   response in standard error format and logs throwable in API request log.
 *
 * Note! Ordering of filters are important. Do not mess with them unless you know what you are
 * doing.
 */
class LifligBasicApiSetup<PrincipalLogT : PrincipalLog>(
    private val logHandler: (RequestResponseLog<PrincipalLogT>) -> Unit,
    /**
     * Set to true to log both request and response bodies. Only logs content types listed in
     * [contentTypesToLog].
     *
     * If you only want to log bodies for unsuccessful responses, use [logHttpBodyOnError] instead.
     *
     * Body logging can be overridden on a per-request basis by calling extension functions provided
     * by this library. If you've set `logHttpBody = false`, you can enable body logging for
     * specific endpoints by calling:
     * - [Request.includeRequestBodyInLog][no.liflig.http4k.setup.includeRequestBodyInLog]
     * - [Request.includeResponseBodyInLog][no.liflig.http4k.setup.includeResponseBodyInLog]
     *
     * If you've set `logHttpBody = true`, you can disable body logging for specific endpoints by
     * calling:
     * - [Request.excludeRequestBodyFromLog][no.liflig.http4k.setup.excludeRequestBodyFromLog]
     * - [Request.excludeResponseBodyFromLog][no.liflig.http4k.setup.excludeResponseBodyFromLog]
     */
    private val logHttpBody: Boolean = false,
    /**
     * Set to true to log request and response bodies only when the API responds with an
     * unsuccessful (non-2XX) response status.
     *
     * You can exclude body logging on a per-request basis (even in case of error), by calling
     * extension functions provided by this library:
     * - [Request.excludeRequestBodyFromLog][no.liflig.http4k.setup.excludeRequestBodyFromLog]
     * - [Request.excludeResponseBodyFromLog][no.liflig.http4k.setup.excludeResponseBodyFromLog]
     */
    private val logHttpBodyOnError: Boolean = false,
    /** If [logHttpBody] is set to true, only these content types will be logged. */
    private val contentTypesToLog: List<ContentType> =
        listOf(
            ContentType.APPLICATION_JSON,
            ContentType.APPLICATION_XML,
            ContentType.APPLICATION_FORM_URLENCODED,
            ContentType.TEXT_PLAIN,
            ContentType.TEXT_XML,
        ),
    /**
     * Header names to black-list from logging. Their values are replaced with `*REDACTED*` in both
     * request and response.
     */
    private val redactedHeaders: List<String> = listOf("authorization", "x-api-key"),
    private val corsPolicy: CorsPolicy? = null,
    /**
     * Allows custom error response body for lens failure in contract if provided. Defaults to
     * Liflig standard.
     */
    private val errorResponseBodyRenderer: ErrorResponseRenderer = StandardErrorResponseBodyRenderer
) {
  fun create(
      /**
       * This param could be set in constructor, but is set here in order to nudge developer to
       * create function closer to its local API setup.
       */
      principalLog: (Request) -> PrincipalLogT?
  ): LifligBasicApiSetupConfig {
    val errorResponseRenderer =
        ContractLensErrorResponseRenderer(delegate = errorResponseBodyRenderer)

    val coreFilters =
        LastResortCatchAllThrowablesFilter()
            // We want this filter to be before the rest of the filters, otherwise we won't get
            // correct CORS headers on responses returned from e.g. CatchUnhandledThrowablesFilter
            .let { if (corsPolicy != null) it.then(ServerFilters.Cors(corsPolicy)) else it }
            .then(RequestContextFilter())
            .then(RequestIdMdcFilter())
            .then(
                LoggingFilter<PrincipalLogT>(
                    principalLog = principalLog,
                    logHandler = logHandler,
                    includeBody = logHttpBody,
                    includeBodyOnError = logHttpBodyOnError,
                    contentTypesToLog = contentTypesToLog,
                    redactedHeaders = redactedHeaders,
                ),
            )
            .then(CatchUnhandledThrowablesFilter())
            .then(PublicExceptionFilter())
            .then(ServerFilters.http4kOpenTelemetryFilter())
            .then(CatchLensFailure(errorResponseRenderer::badRequest))

    return LifligBasicApiSetupConfig(coreFilters, errorResponseRenderer)
  }
}

data class LifligBasicApiSetupConfig(
    val coreFilters: Filter,
    /**
     * Note that contract APIs need to specifically set errorResponseRenderer in order to map lens
     * failures to desirable response, therefore it is returned here and can be utilized during API
     * setup in consumer app. This is because Contract APIs adds [CatchLensFailure]-filter per
     * router which overrides the [CatchLensFailure]-filter set below in core filters. The latter is
     * in place for non-contract-APIs.
     */
    val errorResponseRenderer: ContractLensErrorResponseRenderer
)
