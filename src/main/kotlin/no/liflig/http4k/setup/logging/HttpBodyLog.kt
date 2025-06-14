package no.liflig.http4k.setup.logging

import java.nio.CharBuffer
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import no.liflig.http4k.setup.context.RequestContext
import no.liflig.http4k.setup.logging.HttpBodyLog.Companion.MAX_LOGGED_BODY_SIZE
import no.liflig.http4k.setup.logging.HttpBodyLog.Companion.truncateBody
import no.liflig.logging.getLogger
import no.liflig.logging.rawJson
import org.http4k.core.Body
import org.http4k.core.ContentType
import org.http4k.core.HttpMessage
import org.http4k.core.Request
import org.http4k.core.Response
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
    internal fun raw(content: String) = HttpBodyLog(JsonPrimitive(content))

    /**
     * Extracts the body from the given request/response for logging. Ensures that the size of the
     * body does not exceed [MAX_LOGGED_BODY_SIZE], so it does not break CloudWatch.
     */
    fun from(httpMessage: HttpMessage): HttpBodyLogWithSize {
      var bodySize: Long? = null
      try {
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
      /**
       * Since we're dealing with large bodies here, we want to copy as little as possible. To
       * achieve that, we:
       * 1. Create a slice of the body's payload, with our max log size. This creates a view of the
       *    payload, without copying the underlying buffer.
       * 2. Allocate a CharBuffer with space for our max log size and our <TRUNCATED> suffix. We
       *    need a CharBuffer to convert the body payload to a string, and can avoid an extra copy
       *    by pre-allocating space for the whole payload + suffix.
       * 3. Use [java.nio.charset.CharsetDecoder.decode] to decode the body payload into our
       *    CharBuffer.
       */
      val truncateInput = body.payload.slice(0, MAX_LOGGED_BODY_SIZE)
      val truncateOutput = CharBuffer.allocate(MAX_LOGGED_BODY_SIZE + TRUNCATED_BODY_SUFFIX.length)

      val result =
          StandardCharsets.UTF_8.newDecoder()
              .onMalformedInput(CodingErrorAction.REPLACE)
              .onUnmappableCharacter(CodingErrorAction.REPLACE)
              .decode(truncateInput, truncateOutput, false)
      // We expect the result to be UNDERFLOW, since we reserved extra space for
      // TRUNCATED_BODY_SUFFIX. Any other result is likely an error.
      if (result != CoderResult.UNDERFLOW) {
        // Will be caught and logged by HttpBodyLog.from
        throw IllegalStateException("Failed to decode truncated body (reason: ${result})")
      }

      truncateOutput.append(TRUNCATED_BODY_SUFFIX)

      // Flip advances the limit of the CharBuffer to where we last wrote.
      // Required in order to show the output when converting to string.
      truncateOutput.flip()

      return raw(truncateOutput.toString())
    }

    private fun tryGetJsonBodyForLog(httpMessage: HttpMessage, bodyString: String): JsonElement? {
      /**
       * If this is a request and the body has been parsed as JSON, then we know it's valid JSON.
       *
       * See [no.liflig.http4k.setup.markBodyAsValidJson].
       */
      if (httpMessage is Request && RequestContext.isRequestBodyValidJson(httpMessage)) {
        return rawJson(bodyString, validJson = true)
      }

      // If Content-Type is not JSON (and we have not already parsed it as JSON), then we don't try
      // to parse it here
      if (Header.CONTENT_TYPE(httpMessage)?.value != ContentType.APPLICATION_JSON.value) {
        return null
      }

      // If this is a response (from our server) and the Content-Type is JSON, we assume that we've
      // sent valid JSON
      if (httpMessage is Response) {
        return rawJson(bodyString, validJson = true)
      }

      // Otherwise, we have to pass validJson = false, so rawJson will check if the body is actually
      // valid JSON
      return rawJson(bodyString, validJson = false)
    }

    private val log = getLogger()

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
     * When a request/response body exceeds [MAX_LOGGED_BODY_SIZE], it is truncated to avoid
     * breaking CloudWatch, and this suffix is added (see [truncateBody]).
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
