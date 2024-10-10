package no.liflig.http4k.setup.logging

import java.nio.charset.StandardCharsets
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import mu.KotlinLogging
import no.liflig.http4k.setup.requestBodyIsValidJson
import org.http4k.core.Body
import org.http4k.core.ContentType
import org.http4k.core.HttpMessage
import org.http4k.core.Request
import org.http4k.lens.Header

/**
 * [LoggingFilter] attaches the [RequestResponseLog] to the log as JSON. If the request/response
 * body is also JSON, but included as a String on [RequestResponseLog], then it will be escaped in
 * log output (i.e. '\' added before every string quote). This prevents us from using log analysis
 * tools (such as CloudWatch) to query on fields in the body. To get around this, we use
 * [HttpBodyLog.from] to check if the request/response body was JSON. If the body is valid JSON, we
 * log it as-is, and if it's not, we log it as a string.
 */
@Serializable
@JvmInline
value class HttpBodyLog(val content: JsonElement) {
  override fun toString() = content.toString()

  companion object {
    /**
     * Extracts the body from the given request/response for logging. Ensures that the size of the
     * body does not exceed [MAX_LOGGED_BODY_SIZE], so it does not break CloudWatch.
     */
    fun from(httpMessage: HttpMessage): HttpBodyLogWithSize {
      var bodySize: Long? = null
      try {
        /**
         * body.length is set based on the Content-Length header from the request:
         * https://github.com/http4k/http4k/blob/006bda6ac59b285e7bbb08a1d86fe60e2dbccb6a/http4k-server/jetty/src/main/kotlin/org/http4k/server/Http4kJettyHttpHandler.kt#L32
         *
         * This means we can't trust it: a malicious actor could set Content-Length that is
         * completely different from the actual length of the body. So naively reading the body
         * based on this could expose us to denial-of-service attacks.
         *
         * However, if we do get a Content-Length that says the body is larger than
         * [MAX_LOGGED_BODY_SIZE], we can use that to avoid realizing the body stream below
         * (`httpMessage.body.payload` realizes the underlying stream, reading the whole body - we
         * want to avoid that if we can).
         */
        bodySize = httpMessage.body.length
        if (bodySize != null && bodySize > MAX_LOGGED_BODY_SIZE) {
          return HttpBodyLogWithSize(truncateBody(httpMessage.body), size = bodySize)
        }

        bodySize = httpMessage.body.payload.limit().toLong()
        if (bodySize > MAX_LOGGED_BODY_SIZE) {
          return HttpBodyLogWithSize(truncateBody(httpMessage.body), size = bodySize)
        }

        val bodyString = httpMessage.bodyString()
        bodySize = bodyString.length.toLong()

        val jsonBody = tryGetJsonBodyForLog(httpMessage, bodyString)
        val bodyLog = if (jsonBody != null) HttpBodyLog(jsonBody) else raw(bodyString)

        return HttpBodyLogWithSize(bodyLog, size = bodySize)
      } catch (e: Exception) {
        // We don't want to fail the request just because we failed to read the body for logs. So we
        // just log the exception here and return a failure message.
        log.warn(e) { "Failed to read body for request/response log" }
        return HttpBodyLogWithSize(FAILED_TO_READ_BODY_MESSAGE, size = bodySize)
      }
    }

    private fun truncateBody(body: Body): HttpBodyLog {
      if (body.payload.limit() <= MAX_LOGGED_BODY_SIZE) {
        return raw(StandardCharsets.UTF_8.decode(body.payload).toString())
      }

      val slice = body.payload.slice(0, MAX_LOGGED_BODY_SIZE)
      return raw(StandardCharsets.UTF_8.decode(slice).toString() + TRUNCATED_BODY_SUFFIX)
    }

    private val log = KotlinLogging.logger {}

    internal fun raw(content: String) = HttpBodyLog(JsonPrimitive(content))

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
    internal const val TRUNCATED_BODY_SUFFIX = "<TRUNCATED>"

    internal val FAILED_TO_READ_BODY_MESSAGE = raw("<FAILED TO READ BODY>")

    /**
     * When [excludeRequestBodyFromLog][no.liflig.http4k.setup.excludeRequestBodyFromLog] or
     * [excludeResponseBodyFromLog][no.liflig.http4k.setup.excludeResponseBodyFromLog] have been
     * called in a handler, this message is used instead. We use this instead of setting the body
     * field to null, to avoid confusion over why the body is not included in the request log.
     */
    internal val BODY_EXCLUDED_MESSAGE = raw("<EXCLUDED>")
  }
}

data class HttpBodyLogWithSize(val body: HttpBodyLog, val size: Long?)

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
      @OptIn(ExperimentalSerializationApi::class) (JsonUnquotedLiteral(bodyString))
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
