package no.liflig.http4k.setup.logging

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import net.logstash.logback.marker.Markers
import no.liflig.http4k.setup.errorhandling.ErrorLog
import no.liflig.http4k.setup.normalization.NormalizedStatus
import no.liflig.http4k.setup.normalization.deriveNormalizedStatus
import org.http4k.core.ContentType
import org.http4k.core.Filter
import org.http4k.core.Headers
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
object LoggingFilter {
  private val logger = LoggerFactory.getLogger(LoggingFilter.javaClass)

  init {
    if (logger is NOPLogger) {
      throw RuntimeException("Logging is not configured!")
    }
  }

  /**
   * The maximum size of a CloudWatch log event is 256 KiB.
   *
   * From our experience storing longer lines will result in the line being wrapped, so it no longer
   * will be parsed correctly as JSON.
   *
   * We limit the body size we store to stay below this limit.
   */
  private const val MAX_BODY_LOGGED = 50 * 1024

  private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
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

  private fun String.limitBody() =
      if (length > MAX_BODY_LOGGED) {
        take(MAX_BODY_LOGGED) + "**CAPPED**"
      } else {
        this
      }

  // Only log specific content types.
  // Include lack of content type as it is usually due to an error.
  private fun HttpMessage.shouldLogBody(contentTypesToLog: List<ContentType>): Boolean {
    val contentType = Header.CONTENT_TYPE(this)
    return contentType == null || contentTypesToLog.any { contentType.value == it.value }
  }

  operator fun <T : PrincipalLog> invoke(
      /** Extracts whe [PrincipalLog] from the [Request]. */
      principalLog: (Request) -> T?,
      /** Reads the [ErrorLog] from the [Request], if any. */
      errorLogLens: BiDiLens<Request, ErrorLog?>,
      normalizedStatusLens: BiDiLens<Request, NormalizedStatus?>,
      requestIdChainLens: RequestContextLens<List<UUID>>,
      /** A callback to pass the final log entry to a logger, like SLF4J. */
      logHandler: (RequestResponseLog<T>) -> Unit,
      /**
       * `true` to log both request and response body. Only logs white-listed content types in
       * [contentTypesToLog].
       */
      includeBody: Boolean = true,
      /**
       * Content-Type header values to white-list for logging. Requests or responses with different
       * types will not have their body logged.
       */
      contentTypesToLog: List<ContentType> = listOf(ContentType.APPLICATION_JSON),
      /**
       * Header names to black-list from logging. Their values are replaced with `*REDACTED*` in
       * both request and response.
       */
      redactedHeaders: List<String> = listOf("authorization", "x-api-key"),
  ) = Filter { next ->
    { request ->
      val requestIdChain = requestIdChainLens(request)
      val startTimeInstant = Instant.now()
      val startTime = System.nanoTime()

      // Pass to the next filters.
      val response = next(request)

      val endTimeInstant = Instant.now()
      val duration = Duration.ofNanos(System.nanoTime() - startTime)

      val logRequestBody = includeBody && request.shouldLogBody(contentTypesToLog)
      val logResponseBody = includeBody && response.shouldLogBody(contentTypesToLog)

      val requestBody = if (logRequestBody) request.bodyString() else null
      val responseBody = if (logResponseBody) response.bodyString() else null

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
                      size = request.body.length?.toInt() ?: requestBody?.length,
                      body = requestBody?.limitBody(),
                  ),
              response =
                  ResponseLog(
                      timestamp = endTimeInstant,
                      statusCode = response.status.code,
                      headers = cleanAndNormalizeHeaders(response.headers, redactedHeaders),
                      size = response.body.length?.toInt() ?: responseBody?.length,
                      body = responseBody?.limitBody(),
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

  /**
   * Log handler that will log information from the request using `INFO` level under the requestInfo
   * property. Errors are logged with `WARN` level, except `500` responses, which are logged at
   * `ERROR` level.
   *
   * This relies on using the "net.logstash.logback.encoder.LogstashEncoder" Logback encoder, since
   * it uses special markers that it will parse.
   */
  fun <T : PrincipalLog> createLogHandler(
      /**
       * Errors are always logged, but if this is true, the raw stack trace is dumped directly to
       * the console. This is typically used locally to easy debugging during development.
       *
       * Set to false in production and when using "Structured Logging" (json logs etc), because the
       * stacktrace will break parsing.
       */
      printStacktraceToConsole: Boolean,
      principalLogSerializer: KSerializer<T>,
      /** When `true`, any calls to `/health` that returned `200 OK` will not be logged. */
      suppressSuccessfulHealthChecks: Boolean = true,
  ): (RequestResponseLog<T>) -> Unit = { entry ->
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
        if (printStacktraceToConsole) entry.throwable.printStackTrace()
      }
      else ->
          logger.info(
              logMarker,
              "HTTP request (${response.statusCode}) (${entry.durationMs} ms): ${request.method} ${request.uri}",
          )
    }
  }
}
