@file:Suppress("unused")

package no.liflig.http4k.setup.errorhandling

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.liflig.http4k.setup.createJsonBodyLens
import org.http4k.core.ContentType
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.w3c.dom.Text

/**
 * Standard error response body used for internal APIs for Liflig projects. Uses the
 * [Problem Details](https://datatracker.ietf.org/doc/html/rfc7807) specification.
 * Uses open class instead of data class to give the opportunity to extend the class
 * with custom fields.
 */
@Serializable
open class ErrorResponseBody(
    /** A short, human-readable summary of the problem type. */
    val title: String,
    /** A human-readable explanation specific to this occurrence of the problem. */
    val detail: String? = null,
    /** Status code returned in response. Kept here to have all error context available in body. */
    val status: Int,
    /**
     * A URI reference that identifies the specific occurrence of the problem. Usually an API
     * request (relative) path. E.g. "/api/reservations/1234"
     */
    val instance: String,
    /**
     * An optional URI reference that identifies the problem type. The specification suggests that
     * it should link to human-readable documentation for the problem type (e.g. an HTML page).
     */
    val type: String? = null,
) {
  companion object {
    val bodyLens =
        createJsonBodyLens(
            serializer(),
            errorResponse = "Failed to parse error response",
            jsonInstance = errorResponseBodyJson,
            contentType = ContentType.Text("application/problem+json"))
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

  open override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as ErrorResponseBody

    if (status != other.status) return false
    if (title != other.title) return false
    if (detail != other.detail) return false
    if (instance != other.instance) return false
    if (type != other.type) return false

    return true
  }

  open override fun hashCode(): Int {
    var result = status
    result = 31 * result + title.hashCode()
    result = 31 * result + (detail?.hashCode() ?: 0)
    result = 31 * result + instance.hashCode()
    result = 31 * result + (type?.hashCode() ?: 0)
    return result
  }

  open override fun toString(): String {
    return "ErrorResponseBody(title='$title', detail=$detail, status=$status, instance='$instance', type=$type)"
  }
}

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
