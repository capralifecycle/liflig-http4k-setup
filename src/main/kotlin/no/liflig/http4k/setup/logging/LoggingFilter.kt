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
     * Set to true to log both request and response bodies. Only logs white-listed content types in
     * [contentTypesToLog].
     */
    private val includeBody: Boolean = false,
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

      val requestBody =
          when {
            !includeBody -> null
            !request.shouldLogContentType(contentTypesToLog) -> null
            RequestContext.isRequestBodyExcludedFromLog(request) ->
                HttpBodyLogWithSize(HttpBodyLog.BODY_EXCLUDED_MESSAGE, size = null)
            else -> HttpBodyLog.from(request)
          }
      val responseBody =
          when {
            !includeBody -> null
            !response.shouldLogContentType(contentTypesToLog) -> null
            RequestContext.isResponseBodyExcludedFromLog(request) ->
                HttpBodyLogWithSize(HttpBodyLog.BODY_EXCLUDED_MESSAGE, size = null)
            else -> HttpBodyLog.from(response)
          }

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

  // Only log specific content types.
  // Include lack of content type as it is usually due to an error.
  private fun HttpMessage.shouldLogContentType(contentTypesToLog: List<ContentType>): Boolean {
    val contentType = Header.CONTENT_TYPE(this)
    return contentType == null || contentTypesToLog.any { contentType.value == it.value }
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
