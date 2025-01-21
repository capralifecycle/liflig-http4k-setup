@file:Suppress("unused")

package no.liflig.http4k.setup.errorhandling

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.liflig.http4k.setup.createJsonBodyLens
import org.http4k.core.ContentType
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.BiDiBodyLens

/**
 * Standard error response body used for internal APIs for Liflig projects. Uses the
 * [Problem Details](https://datatracker.ietf.org/doc/html/rfc7807) specification.
 */
@Serializable
data class ErrorResponseBody(
    override val title: String,
    override val detail: String? = null,
    override val status: Int,
    override val instance: String,
    override val type: String? = null,
) : BaseErrorResponseBody {
  companion object {
    val bodyLens = BaseErrorResponseBody.createBodyLens(serializer())
  }

  /**
   * Utility function to create a response from an error response body, using the [status] from the
   * body.
   */
  fun toResponse(): Response {
    // status should always be a valid HTTP status code, but we fall back to 500 just in case
    val status = Status.fromCode(this.status) ?: Status.INTERNAL_SERVER_ERROR
    return Response(status).with(bodyLens of this)
  }
}

/**
 * Base interface for error response bodies following the
 * [Problem Details](https://datatracker.ietf.org/doc/html/rfc7807) specification. A default
 * implementation is provided through [ErrorResponseBody].
 *
 * If you want to extend the error response body format with your own custom fields, you can
 * implement this interface instead of using the default [ErrorResponseBody]. When creating an
 * http4k body lens for your type, you can call [BaseErrorResponseBody.createBodyLens] with your
 * type's serializer to use the same JSON serialization config and content type
 * (`application/problem+json`) as the default [ErrorResponseBody].
 */
interface BaseErrorResponseBody {
  /** A short, human-readable summary of the problem type. */
  val title: String

  /** A human-readable explanation specific to this occurrence of the problem. */
  val detail: String?

  /** Status code returned in response. Kept here to have all error context available in body. */
  val status: Int

  /**
   * A URI reference that identifies the specific occurrence of the problem. Usually an API path
   * (relative). E.g. "/api/reservations/1234".
   */
  val instance: String

  /**
   * An optional URI reference that identifies the problem type. The specification suggests that it
   * should link to human-readable documentation for the problem type (e.g. an HTML page).
   */
  val type: String?

  companion object {
    fun <T : BaseErrorResponseBody> createBodyLens(serializer: KSerializer<T>): BiDiBodyLens<T> {
      return createJsonBodyLens(
          serializer,
          errorResponse = "Failed to parse error response",
          jsonInstance = errorResponseBodyJson,
          contentType = problemDetailsContentType,
      )
    }
  }
}

/**
 * Custom Content-Type for Problem Details error response bodies, as specified in:
 * https://datatracker.ietf.org/doc/html/rfc7807#section-3
 */
internal val problemDetailsContentType = ContentType.Text("application/problem+json")

/**
 * We use a custom Json instance here with `explicitNulls = false`, since we don't want to include
 * `"detail": null` and `"type": null` on every error response that don't have these (which are most
 * responses).
 */
internal val errorResponseBodyJson = Json {
  encodeDefaults = true
  ignoreUnknownKeys = true
  explicitNulls = false
}
