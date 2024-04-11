package no.liflig.http4k.setup.normalization

import org.http4k.core.Response
import org.http4k.core.Status

internal fun deriveNormalizedStatus(response: Response): NormalizedStatus {
  val s = response.status
  return when {
    s.successful || s.informational || s.redirection -> NormalizedStatus.Ok()
    s.clientError ->
        when (s) {
          Status.UNAUTHORIZED -> NormalizedStatus.ClientError(ClientErrorCategory.UNAUTHORIZED)
          Status.NOT_FOUND -> NormalizedStatus.ClientError(ClientErrorCategory.NOT_FOUND)
          else -> NormalizedStatus.ClientError(ClientErrorCategory.BAD_REQUEST)
        }
    s == Status.SERVICE_UNAVAILABLE -> NormalizedStatus.ServiceUnavailable()
    else -> NormalizedStatus.InternalServerError()
  }
}
