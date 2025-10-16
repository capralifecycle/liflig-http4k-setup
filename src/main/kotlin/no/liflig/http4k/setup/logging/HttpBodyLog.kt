package no.liflig.http4k.setup.logging

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import no.liflig.http4k.setup.context.RequestContext
import no.liflig.http4k.setup.context.ResponseContext
import no.liflig.http4k.setup.logging.HttpBodyLog.Companion.MAX_LOGGED_BODY_SIZE
import no.liflig.logging.RawJson
import no.liflig.logging.getLogger
import no.liflig.logging.rawJson
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
 * log it as-is ([JsonBodyLog]), and if it's not, we log it as a string ([StringBodyLog]).
 */
@Serializable(with = HttpBodyLogSerializer::class)
sealed interface HttpBodyLog {
  companion object {
    /**
     * Extracts the body from the given request/response for logging. Ensures that the size of the
     * body does not exceed [MAX_LOGGED_BODY_SIZE], so it does not break CloudWatch.
     */
    fun from(httpMessage: HttpMessage): HttpBodyLogWithSize {
      var bodySize: Long? = null
      try {
        /**
         * If the body has passed through [no.liflig.http4k.setup.createJsonBodyLens], then we know
         * it's valid JSON.
         *
         * See [no.liflig.http4k.setup.markBodyAsValidJson].
         */
        val validJsonBody: String? =
            when (httpMessage) {
              is Request -> RequestContext.getValidJsonRequestBody(httpMessage)
              is Response -> ResponseContext.getValidJsonResponseBody(httpMessage)
              else -> null
            }
        if (validJsonBody != null) {
          bodySize = validJsonBody.length.toLong()
          if (bodySize > MAX_LOGGED_BODY_SIZE) {
            return HttpBodyLogWithSize(BODY_TOO_LARGE_MESSAGE, bodySize)
          }
          return HttpBodyLogWithSize(
              // We can pass validJson = true here, since we know that the body has been
              // successfully parsed as JSON if it passed through the JSON body lens
              JsonBodyLog(rawJson(validJsonBody, validJson = true)),
              bodySize,
          )
        }

        // If body has been encoded (e.g. with gzip), then it doesn't make sense to log it
        if (
            httpMessage.header("Content-Encoding") != null ||
                httpMessage.header("Transfer-Encoding") != null
        ) {
          return HttpBodyLogWithSize(BODY_ENCODED_MESSAGE, size = null)
        }

        bodySize = httpMessage.body.payload.limit().toLong()
        if (bodySize > MAX_LOGGED_BODY_SIZE) {
          return HttpBodyLogWithSize(BODY_TOO_LARGE_MESSAGE, bodySize)
        }

        val bodyString = httpMessage.bodyString()
        bodySize = bodyString.length.toLong()

        // If Content-Type is application/json, then we try to include it as JSON on the log
        // (passing validJson = false to rawJson, since we can't be sure that the JSON is valid)
        if (Header.CONTENT_TYPE(httpMessage)?.value == ContentType.APPLICATION_JSON.value) {
          return HttpBodyLogWithSize(
              JsonBodyLog(rawJson(bodyString, validJson = false)),
              size = bodySize,
          )
        }

        // If Content-Type is not JSON, then we just include it as a string
        return HttpBodyLogWithSize(StringBodyLog(bodyString), size = bodySize)
      } catch (e: Exception) {
        // We don't want to fail the request just because we failed to read the body for logs. So we
        // just log the exception here and return a failure message.
        log.warn(e) { "Failed to read body for request/response log" }
        return HttpBodyLogWithSize(FAILED_TO_READ_BODY_MESSAGE, size = bodySize)
      }
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
    internal const val MAX_LOGGED_BODY_SIZE = 128 * 1024

    internal val BODY_TOO_LARGE_MESSAGE = StringBodyLog("<Body too large for log>")

    internal val FAILED_TO_READ_BODY_MESSAGE = StringBodyLog("<Failed to read body>")

    internal val BODY_ENCODED_MESSAGE = StringBodyLog("<Encoded>")
  }
}

/** See [HttpBodyLog]. */
internal class JsonBodyLog(internal val body: RawJson) : HttpBodyLog {
  override fun toString(): String = body.toString()

  override fun equals(other: Any?): Boolean = other is JsonBodyLog && this.body == other.body

  override fun hashCode(): Int = body.hashCode()
}

/** See [HttpBodyLog]. */
internal class StringBodyLog(internal val body: String) : HttpBodyLog {
  override fun toString(): String = body

  override fun equals(other: Any?): Boolean = other is StringBodyLog && this.body == other.body

  override fun hashCode(): Int = body.hashCode()
}

/** See [HttpBodyLog]. */
internal object HttpBodyLogSerializer : KSerializer<HttpBodyLog> {
  private val rawJsonSerializer = RawJson.serializer()
  private val stringSerializer = String.serializer()

  override val descriptor: SerialDescriptor
    get() = rawJsonSerializer.descriptor

  override fun serialize(encoder: Encoder, value: HttpBodyLog) {
    when (value) {
      is JsonBodyLog -> rawJsonSerializer.serialize(encoder, value.body)
      is StringBodyLog -> stringSerializer.serialize(encoder, value.body)
    }
  }

  override fun deserialize(decoder: Decoder): HttpBodyLog {
    try {
      val rawJson = rawJsonSerializer.deserialize(decoder)
      return JsonBodyLog(rawJson)
    } catch (_: Exception) {
      val string = stringSerializer.deserialize(decoder)
      return StringBodyLog(string)
    }
  }
}

data class HttpBodyLogWithSize(val body: HttpBodyLog, val size: Long?)
