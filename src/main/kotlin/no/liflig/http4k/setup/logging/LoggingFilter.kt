package no.liflig.http4k.setup.logging

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import net.logstash.logback.marker.Markers
import no.liflig.http4k.setup.LifligUserPrincipalLog
import no.liflig.http4k.setup.errorhandling.ErrorLog
import no.liflig.http4k.setup.excludeRequestBodyFromLogLens
import no.liflig.http4k.setup.excludeResponseBodyFromLogLens
import no.liflig.http4k.setup.normalization.NormalizedStatus
import no.liflig.http4k.setup.normalization.deriveNormalizedStatus
import org.http4k.core.ContentType
import org.http4k.core.Filter
import org.http4k.core.Headers
import org.http4k.core.HttpHandler
import org.http4k.core.HttpMessage
import org.http4k.core.Request
import org.http4k.lens.BiDiLens
import org.http4k.lens.Header
import org.http4k.lens.RequestContextLens
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.NOPLogger

/** Filter to handle request logging. */
class LoggingFilter<T : PrincipalLog>(
    /** Extracts whe [PrincipalLog] from the [Request]. */
    private val principalLog: (Request) -> T?,
    /** Reads the [ErrorLog] from the [Request], if any. */
    private val errorLogLens: BiDiLens<Request, ErrorLog?>,
    private val normalizedStatusLens: BiDiLens<Request, NormalizedStatus?>,
    private val requestIdChainLens: RequestContextLens<List<UUID>>,
    /** A callback to pass the final log entry to a logger, like SLF4J. */
    private val logHandler: (RequestResponseLog<T>) -> Unit,
    /**
     * `true` to log both request and response body. Only logs white-listed content types in
     * [contentTypesToLog].
     */
    private val includeBody: Boolean = true,
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
      val requestIdChain = requestIdChainLens(request)
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
            excludeRequestBodyFromLogLens(request) ->
                HttpBodyLogWithSize(HttpBodyLog.BODY_EXCLUDED_MESSAGE, size = null)
            else -> HttpBodyLog.from(request)
          }
      val responseBody =
          when {
            !includeBody -> null
            !response.shouldLogContentType(contentTypesToLog) -> null
            excludeResponseBodyFromLogLens(request) ->
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
              throwable = errorLogLens(request)?.throwable,
              status = normalizedStatusLens(request) ?: deriveNormalizedStatus(response),
              thread = Thread.currentThread().name,
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
    // We use an SLF4J logger instead of KotlinLogging here, as KotlinLogging messed up the file
    // location when using atLevel below
    private val logger = LoggerFactory.getLogger(LoggingFilter::class.java)

    init {
      if (logger is NOPLogger) throw RuntimeException("Logging is not configured!")
    }

    private val json = Json {
      encodeDefaults = true
      ignoreUnknownKeys = true
    }

    /**
     * Log handler that will log information from the request using `INFO` level under the
     * requestInfo property. Errors are logged with `WARN` level, except `500` responses, which are
     * logged at `ERROR` level.
     *
     * This relies on using the "net.logstash.logback.encoder.LogstashEncoder" Logback encoder,
     * since it uses special markers that it will parse.
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
     * Log handler that will log information from the request using `INFO` level under the
     * requestInfo property. Errors are logged with `WARN` level, except `500` responses, which are
     * logged at `ERROR` level.
     *
     * This relies on using the "net.logstash.logback.encoder.LogstashEncoder" Logback encoder,
     * since it uses special markers that it will parse.
     */
    fun <T : PrincipalLog> createLogHandler(
        /**
         * Serializer for custom principal data class when you do not want to use Liflig default
         * [LifligUserPrincipalLog].
         */
        principalLogSerializer: KSerializer<T>,
        /** When `true`, any calls to `/health` that returned `200 OK` will not be logged. */
        suppressSuccessfulHealthChecks: Boolean = true,
    ): (RequestResponseLog<T>) -> Unit = { entry ->
      logEntry(entry, principalLogSerializer, suppressSuccessfulHealthChecks)
    }

    private fun <T : PrincipalLog> logEntry(
        entry: RequestResponseLog<T>,
        principalLogSerializer: KSerializer<T>,
        suppressSuccessfulHealthChecks: Boolean,
    ) {
      val request = entry.request
      val response = entry.response

      val logMarker: Marker by lazy {
        Markers.appendRaw(
            "requestInfo",
            json.encodeToString(RequestResponseLog.serializer(principalLogSerializer), entry),
        )
      }

      when {
        suppressSuccessfulHealthChecks &&
            request.uri == "/health" &&
            response.statusCode == 200 &&
            entry.throwable == null -> {
          // NoOp
        }
        entry.throwable != null -> {
          val level = if (entry.response.statusCode == 500) Level.ERROR else Level.WARN
          logger
              .atLevel(level)
              .addMarker(logMarker)
              .setCause(entry.throwable)
              .log(
                  "HTTP request failed (${response.statusCode}) (${entry.durationMs} ms): ${request.method} ${request.uri}",
              )
        }
        else ->
            logger.info(
                logMarker,
                "HTTP request (${response.statusCode}) (${entry.durationMs} ms): ${request.method} ${request.uri}",
            )
      }
    }
  }
}
