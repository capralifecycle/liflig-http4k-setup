package no.liflig.http4k.setup.errorhandling

import no.liflig.http4k.setup.normalization.NormalizedStatus
import org.http4k.contract.ErrorResponseRenderer
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.with
import org.http4k.lens.BiDiLens
import org.http4k.lens.LensFailure

/**
 * Responsible for putting lens failure (exception) in request context for logging and providing
 * body in proper json-format by [delegate].
 *
 * Note! Must be applied to contract renderer to catch any contractual errors by consumer of
 * library. ContractRoutingHttpHandler has its own CatchLensFailure-filter set up so any contractual
 * errors are swallowed before it reaches the global RequestLensFailureFilter. Therefore we must
 * also provide this handler to the contract renderer so that it is called in this filter.
 *
 * E.g. /path/to/api bind contract { renderer = OpenApi3( apiInfo = ApiInfo(...),
 * errorResponseRenderer = ContractErrorResponseRenderer(...) ) }
 */
class ContractLensErrorResponseRenderer(
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
