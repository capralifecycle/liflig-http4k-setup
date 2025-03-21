package no.liflig.http4k.setup.errorhandling

import no.liflig.http4k.setup.JsonBodyLensFailure
import no.liflig.http4k.setup.context.RequestContext
import org.http4k.contract.ErrorResponseRenderer
import org.http4k.core.Request
import org.http4k.core.Response
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
    private val delegate: ErrorResponseRenderer,
) : ErrorResponseRenderer {
  override fun badRequest(lensFailure: LensFailure): Response {
    val request = lensFailure.target
    check(request is Request)

    // If the LensFailure was a JsonBodyLensFailure from createJsonBodyLens, then we don't want
    // the redundant wrapper exception
    val loggedException = (lensFailure.cause as? JsonBodyLensFailure)?.cause ?: lensFailure
    RequestContext.setExceptionForLog(request, loggedException)

    return delegate.badRequest(lensFailure)
  }
}
