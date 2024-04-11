package no.liflig.http4k.setup.errorhandling

import kotlinx.serialization.Serializable
import org.http4k.core.Body
import org.http4k.format.KotlinxSerialization.auto

/**
 * Standard error response body used for internal APIs for Liflig projects. Uses the RFC 7807
 * standard : https://datatracker.ietf.org/doc/html/rfc7807
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
}
