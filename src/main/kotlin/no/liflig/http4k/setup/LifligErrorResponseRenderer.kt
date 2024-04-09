package no.liflig.http4k.setup

import no.liflig.logging.ErrorLog
import no.liflig.logging.NormalizedStatus
import no.liflig.logging.http4k.ErrorResponseRendererWithLogging
import org.http4k.contract.ErrorResponseRenderer
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.with
import org.http4k.lens.BiDiLens
import org.http4k.lens.LensFailure

/**
 * Responsible for putting throwables in request context, so it is set in API request log. Based on
 * [ErrorResponseRendererWithLogging].
 */
class LifligErrorResponseRenderer(
    private val errorLogLens: BiDiLens<Request, ErrorLog?>,
    private val normalizedStatusLens: BiDiLens<Request, NormalizedStatus?>,
    private val delegate: ErrorResponseRenderer,
) : ErrorResponseRenderer {
  override fun badRequest(lensFailure: LensFailure): Response {
    val target = lensFailure.target
    check(target is Request)

    // RequestContext is bound to the Request object.
    target.with(errorLogLens of ErrorLog(lensFailure))

    // Reset any normalized status in case it is set earlier.
    // RequestContext is bound to the Request object.
    target.with(normalizedStatusLens of null)

    return delegate.badRequest(lensFailure)
  }
}
