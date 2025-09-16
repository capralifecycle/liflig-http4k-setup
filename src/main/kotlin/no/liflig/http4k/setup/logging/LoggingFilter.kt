package no.liflig.http4k.setup.logging

import java.time.Duration
import java.time.Instant
import kotlinx.serialization.KSerializer
import no.liflig.http4k.setup.LifligUserPrincipalLog
import no.liflig.http4k.setup.context.RequestContext
import no.liflig.http4k.setup.filters.RequestIdMdcFilter
import no.liflig.http4k.setup.normalization.NormalizedStatus
import no.liflig.logging.LogLevel
import no.liflig.logging.getLogger
import org.http4k.core.ContentType
import org.http4k.core.Filter
import org.http4k.core.Headers
import org.http4k.core.HttpHandler
import org.http4k.core.HttpMessage
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.lens.Header

/** Filter to handle request logging. */
class LoggingFilter<PrincipalLogT : PrincipalLog>(
    /** Extracts the [PrincipalLog] from the [Request]. */
    private val principalLog: (Request) -> PrincipalLogT?,
    /**
     * A callback to pass the final log entry to a logger, like liflig-logging.
     *
     * A default implementation is provided in [LoggingFilter.createLogHandler].
     */
    private val logHandler: (RequestResponseLog<PrincipalLogT>) -> Unit,
    /**
     * Set to true to log both request and response bodies. Only logs content types listed in
     * [contentTypesToLog].
     *
     * If you only want to log bodies for unsuccessful responses, use [includeBodyOnError] instead.
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
    private val includeBody: Boolean = false,
    /**
     * Set to true to log request and response bodies only when the API responds with an
     * unsuccessful (non-2XX) response status.
     *
     * You can exclude body logging on a per-request basis (even in case of error), by calling
     * extension functions provided by this library:
     * - [Request.excludeRequestBodyFromLog][no.liflig.http4k.setup.excludeRequestBodyFromLog]
     * - [Request.excludeResponseBodyFromLog][no.liflig.http4k.setup.excludeResponseBodyFromLog]
     */
    private val includeBodyOnError: Boolean = false,
    /**
     * Content-Type header values to white-list for logging. Requests or responses with different
     * types will not have their body logged.
     */
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
) : Filter {
  override fun invoke(nextHandler: HttpHandler): HttpHandler {
    return { request ->
      val requestIdChain = RequestIdMdcFilter.requestIdChainLens(request)
      val startTimeInstant = Instant.now()
      val startTime = System.nanoTime()

      // Pass to the next filters.
      val response = nextHandler(request)

      val endTimeInstant = Instant.now()
      val duration = Duration.ofNanos(System.nanoTime() - startTime)

      val requestBody = getBodyLog(httpMessage = request, request, response)
      val responseBody = getBodyLog(httpMessage = response, request, response)

      val logEntry =
          RequestResponseLog(
              timestamp = Instant.now(),
              requestId = requestIdChain.last(),
              requestIdChain = requestIdChain,
              request =
                  RequestLog(
                      timestamp = startTimeInstant,
                      method = request.method.toString(),
                      uri = request.uri.toString(),
                      headers = cleanAndNormalizeHeaders(request.headers, redactedHeaders),
                      size = requestBody?.size,
                      body = requestBody?.body,
                  ),
              response =
                  ResponseLog(
                      timestamp = endTimeInstant,
                      statusCode = response.status.code,
                      headers = cleanAndNormalizeHeaders(response.headers, redactedHeaders),
                      size = responseBody?.size,
                      body = responseBody?.body,
                  ),
              principal = principalLog(request),
              durationMs = duration.toMillis(),
              throwable = RequestContext.getExceptionForLog(request),
              status = NormalizedStatus.from(response),
              thread = Thread.currentThread().name,
              logLevel = RequestContext.getRequestLogLevel(request),
          )

      logHandler(logEntry)

      response
    }
  }

  private fun getBodyLog(
      httpMessage: HttpMessage,
      request: Request,
      response: Response,
  ): HttpBodyLogWithSize? {
    return if (shouldLogBody(httpMessage, request, response)) {
      HttpBodyLog.from(httpMessage)
    } else {
      null
    }
  }

  @Suppress("RedundantIf") // We want to make boolean logic here as explicit as possible
  private fun shouldLogBody(
      httpMessage: HttpMessage,
      request: Request,
      response: Response,
  ): Boolean {
    if (!shouldLogContentType(httpMessage)) {
      return false
    }

    /**
     * If the user has enabled [includeBodyOnError], then we log the request/response body if we
     * returned an unsuccessful response.
     */
    if (includeBodyOnError) {
      if (!response.status.successful) {
        if (isBodyExcludedFromLog(httpMessage, request)) {
          return false
        }
        return true
      }
    }

    if (includeBody) {
      // If user has set bodies to be logged by default, then we check if it's been overridden to
      // be excluded from the log for this request
      if (isBodyExcludedFromLog(httpMessage, request)) {
        return false
      }
      return true
    } else {
      // If user has set bodies to not be logged by default, then we check if it's been overridden
      // to be included in the log for this request
      if (isBodyIncludedInLog(httpMessage, request)) {
        return true
      }
      return false
    }
  }

  /**
   * Checks if the request/response's Content-Type is among our allowed content-types for logging.
   */
  private fun shouldLogContentType(httpMessage: HttpMessage): Boolean {
    val contentType = Header.CONTENT_TYPE(httpMessage)
    return contentType == null || contentTypesToLog.any { contentType.value == it.value }
  }

  /**
   * The user can set `includeBody` to true on the `LoggingFilter`, but override it on a
   * per-endpoint basis with [no.liflig.http4k.setup.excludeRequestBodyFromLog] /
   * [no.liflig.http4k.setup.excludeResponseBodyFromLog].
   */
  private fun isBodyExcludedFromLog(httpMessage: HttpMessage, request: Request): Boolean {
    return if (httpMessage === request) {
      RequestContext.isRequestBodyExcludedFromLog(request)
    } else {
      RequestContext.isResponseBodyExcludedFromLog(request)
    }
  }

  /**
   * The user can set `includeBody` to false on the `LoggingFilter`, but override it on a
   * per-endpoint basis with [no.liflig.http4k.setup.includeRequestBodyInLog] /
   * [no.liflig.http4k.setup.includeResponseBodyInLog].
   */
  private fun isBodyIncludedInLog(httpMessage: HttpMessage, request: Request): Boolean {
    return if (httpMessage === request) {
      RequestContext.isRequestBodyIncludedInLog(request)
    } else {
      RequestContext.isResponseBodyIncludedInLog(request)
    }
  }

  private fun cleanAndNormalizeHeaders(
      headers: Headers,
      redactedHeaders: List<String>,
  ): List<Map<String, String?>> =
      headers.map { (name, value) ->
        mapOf(
            "name" to name,
            "value" to
                when {
                  redactedHeaders.any { it.equals(name, ignoreCase = true) } -> "*REDACTED*"
                  else -> value
                },
        )
      }

  companion object {
    private val log = getLogger()

    /**
     * Log handler that logs request/response data in a "requestInfo" log field. If the HTTP handler
     * threw an exception, that is also attached to the log.
     *
     * Internal server errors (500) are logged at the ERROR log level, other statuses are logged at
     * INFO.
     *
     * Uses default [LifligUserPrincipalLog] serializer. Either because you actually want to use
     * this principalLog class for logging or because you do not have a concept principal and do not
     * want clutter code with unused things.
     */
    fun createLogHandler(
        /** When `true`, any calls to `/health` that returned `200 OK` will not be logged. */
        suppressSuccessfulHealthChecks: Boolean = true,
    ): (RequestResponseLog<LifligUserPrincipalLog>) -> Unit = { entry ->
      logEntry(entry, LifligUserPrincipalLog.serializer(), suppressSuccessfulHealthChecks)
    }

    /**
     * Returns a log handler that logs request/response data in a "requestInfo" log field. If the
     * HTTP handler threw an exception, that is also attached to the log.
     *
     * Internal server errors (500) are logged at the ERROR log level, other statuses are logged at
     * INFO.
     */
    fun <PrincipalLogT : PrincipalLog> createLogHandler(
        /**
         * Serializer for custom principal data class when you do not want to use Liflig default
         * [LifligUserPrincipalLog].
         */
        principalLogSerializer: KSerializer<PrincipalLogT>,
        /** When `true`, any calls to `/health` that returned `200 OK` will not be logged. */
        suppressSuccessfulHealthChecks: Boolean = true,
    ): (RequestResponseLog<PrincipalLogT>) -> Unit = { entry ->
      logEntry(entry, principalLogSerializer, suppressSuccessfulHealthChecks)
    }

    private fun <PrincipalLogT : PrincipalLog> logEntry(
        entry: RequestResponseLog<PrincipalLogT>,
        principalLogSerializer: KSerializer<PrincipalLogT>,
        suppressSuccessfulHealthChecks: Boolean,
    ) {
      val request = entry.request
      val response = entry.response

      // Suppress successful health checks
      if (suppressSuccessfulHealthChecks &&
          request.uri == "/health" &&
          response.statusCode == 200 &&
          entry.throwable == null) {
        return
      }

      val logLevel: LogLevel =
          when {
            entry.logLevel != null -> entry.logLevel
            response.statusCode == 500 -> LogLevel.ERROR
            else -> LogLevel.INFO
          }

      log.at(logLevel, cause = entry.throwable) {
        field(
            "requestInfo",
            entry,
            serializer = RequestResponseLog.serializer(principalLogSerializer),
        )
        if (response.statusCode < 400) {
          "HTTP request (${response.statusCode}) (${entry.durationMs} ms): ${request.method} ${request.uri}"
        } else {
          "HTTP request failed (${response.statusCode}) (${entry.durationMs} ms): ${request.method} ${request.uri}"
        }
      }
    }
  }
}
