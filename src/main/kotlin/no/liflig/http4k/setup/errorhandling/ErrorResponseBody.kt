package no.liflig.http4k.setup.errorhandling

import kotlinx.serialization.Serializable
import org.http4k.core.Body
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto

/**
 * Standard error response body used for internal APIs for Liflig projects. Uses the 'Problem
 * Details' specification.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7807">Problem Details specification</a>
 */
@Serializable
data class ErrorResponseBody(
    /** A short, human-readable summary of the problem type. */
    val title: String,
    /** A human-readable explanation specific to this occurrence of the problem. */
    val detail: String?,
    /** Status code returned in response. Kept here to have all error context available in body. */
    val status: Int,
    /**
     * A URI reference that identifies the specific occurrence of the problem. Usually an API
     * request (relative) path. E.g. "/api/reservations/1234"
     */
    val instance: String
) {
  companion object {
    val bodyLens = Body.auto<ErrorResponseBody>().toLens()
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
