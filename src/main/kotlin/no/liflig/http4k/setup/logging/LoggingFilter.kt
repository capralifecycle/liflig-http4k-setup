package no.liflig.http4k.setup.logging

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonUnquotedLiteral
import net.logstash.logback.marker.Markers
import no.liflig.http4k.setup.LifligUserPrincipalLog
import no.liflig.http4k.setup.errorhandling.ErrorLog
import no.liflig.http4k.setup.excludeRequestBodyFromLogLens
import no.liflig.http4k.setup.excludeResponseBodyFromLogLens
import no.liflig.http4k.setup.normalization.NormalizedStatus
import no.liflig.http4k.setup.normalization.deriveNormalizedStatus
import no.liflig.http4k.setup.requestBodyIsValidJson
import org.http4k.core.ContentType
import org.http4k.core.Filter
import org.http4k.core.Headers
import org.http4k.core.HttpHandler
import org.http4k.core.HttpMessage
import org.http4k.core.Request
import org.http4k.core.Response
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
    private val contentTypesToLog: List<ContentType> = listOf(ContentType.APPLICATION_JSON),
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

      val logRequestBody = includeBody && request.shouldLogBody(contentTypesToLog)
      val logResponseBody = includeBody && response.shouldLogBody(request, contentTypesToLog)

      val requestBody = if (logRequestBody) readLimitedBody(request) else null
      val responseBody = if (logResponseBody) readLimitedBody(response) else null

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

  data class ReadBodyResult(val body: BodyLog, val size: Long)

  /**
   * See [MAX_LOGGED_BODY_SIZE].
   *
   * Previously, we called [HttpMessage.bodyString] when logging request/response bodies, and only
   * capped the size of the returned string. However, this reads the entire body into memory, and
   * led to [java.util.concurrent.TimeoutException] when the body was truly massive. In these cases,
   * the endpoint should probably exclude body logging with
   * [excludeRequestBodyFromLog][no.liflig.http4k.setup.excludeRequestBodyFromLog] or
   * [excludeResponseBodyFromLog][no.liflig.http4k.setup.excludeResponseBodyFromLog], but the
   * logging filter should also not read more of the body than it intends to log.
   *
   * We tried an implementation of this using [java.io.InputStream.readNBytes] to read only
   * [MAX_LOGGED_BODY_SIZE] bytes from the body - but that just returned an empty string
   */
  private fun readLimitedBody(httpMessage: HttpMessage): ReadBodyResult? {
    try {
      /**
       * body.length is set based on the Content-Length header from the request:
       * https://github.com/http4k/http4k/blob/006bda6ac59b285e7bbb08a1d86fe60e2dbccb6a/http4k-server/jetty/src/main/kotlin/org/http4k/server/Http4kJettyHttpHandler.kt#L32
       *
       * This means we can't trust it: a malicious actor could set Content-Length that is completely
       * different from the actual length of the body. So naively reading the body based on this
       * could expose us to denial-of-service attacks.
       *
       * However, if we do get a Content-Length that says the body is larger than
       * [MAX_LOGGED_BODY_SIZE], we can use that to avoid realizing the body stream below
       * (`httpMessage.body.payload` realizes the underlying stream, reading the whole body - we
       * want to avoid that if we can).
       */
      val bodyLength = httpMessage.body.length
      if (bodyLength != null && bodyLength > MAX_LOGGED_BODY_SIZE) {
        return ReadBodyResult(BODY_TOO_LONG_MESSAGE, size = bodyLength)
      }

      val bufferSize = httpMessage.body.payload.limit()
      if (bufferSize > MAX_LOGGED_BODY_SIZE) {
        return ReadBodyResult(BODY_TOO_LONG_MESSAGE, size = bufferSize.toLong())
      }

      val bodyString = httpMessage.bodyString()

      val jsonBody = tryGetJsonBodyForLog(httpMessage, bodyString)
      val bodyLog = if (jsonBody != null) BodyLog(jsonBody) else BodyLog.raw(bodyString)

      return ReadBodyResult(bodyLog, size = bodyString.length.toLong())
    } catch (e: Exception) {
      // We don't want to fail the request just because we failed to read the body for logs. So we
      // just log the exception here and return null for the body.
      logger.atError().setCause(e).log("Failed to read body for request/response log")
      return null
    }
  }

  // Only log specific content types.
  // Include lack of content type as it is usually due to an error.
  private fun HttpMessage.shouldLogContentType(contentTypesToLog: List<ContentType>): Boolean {
    val contentType = Header.CONTENT_TYPE(this)
    return contentType == null || contentTypesToLog.any { contentType.value == it.value }
  }

  private fun Request.shouldLogBody(contentTypesToLog: List<ContentType>): Boolean {
    if (excludeRequestBodyFromLogLens(this)) {
      return false
    }
    return this.shouldLogContentType(contentTypesToLog)
  }

  private fun Response.shouldLogBody(
      request: Request,
      contentTypesToLog: List<ContentType>,
  ): Boolean {
    if (excludeResponseBodyFromLogLens(request)) {
      return false
    }
    return this.shouldLogContentType(contentTypesToLog)
  }

  companion object {
    /**
     * The maximum size of a CloudWatch log event is 256 KiB.
     *
     * From our experience storing longer lines will result in the line being wrapped, so it no
     * longer will be parsed correctly as JSON.
     *
     * We limit the body size we store to stay below this limit.
     */
    internal const val MAX_LOGGED_BODY_SIZE = 50 * 1024

    /**
     * When a request/response body exceeds [MAX_LOGGED_BODY_SIZE], this string is logged instead.
     */
    internal val BODY_TOO_LONG_MESSAGE = BodyLog.raw("<EXCEEDS MAX LOG SIZE>")

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

private fun tryGetJsonBodyForLog(httpMessage: HttpMessage, bodyString: String): JsonElement? {
  return when {
    // If Content-Type is not application/json, then this is not a JSON body
    Header.CONTENT_TYPE(httpMessage)?.value != ContentType.APPLICATION_JSON.value -> null
    /**
     * We only want to include the body string as raw JSON if we trust the body (see
     * [no.liflig.http4k.setup.markBodyAsValidJson]). In addition, the body can't include newlines,
     * as that makes CloudWatch interpret the body as multiple different log messages (newlines are
     * used to separate log entries).
     */
    (httpMessage is Request && !requestBodyIsValidJson(httpMessage)) ||
        bodyString.containsUnescapedOrUnquotedNewlines() -> {
      try {
        return Json.parseToJsonElement(bodyString)
      } catch (_: Exception) {
        return null
      }
    }

    // JsonUnquotedLiteral throws if given "null", so we have to check that here first
    bodyString == "null" -> JsonNull
    else -> {
      @OptIn(ExperimentalSerializationApi::class) JsonUnquotedLiteral(bodyString)
    }
  }
}

private fun String.containsUnescapedOrUnquotedNewlines(): Boolean {
  var insideQuote = false

  for ((index, char) in this.withIndex()) {
    when (char) {
      '"' -> {
        insideQuote = !insideQuote
      }
      '\n' -> {
        if (!insideQuote) {
          return true
        }
        if (index == 0) {
          return true
        }
        if (this[index - 1] != '\\') {
          return true
        }
      }
    }
  }

  return false
}
