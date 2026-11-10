package no.liflig.http4k.setup.errorhandling

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

    // If the cause of the LensFailure was a PublicException, then we use that as the error
    // response.
    // We search the whole cause chain, since kotlinx-serialization (since 1.12) wraps exceptions
    // thrown from `@Serializable` class constructors/init blocks in a `JsonDecodingException`.
    val publicException = lensFailure.cause?.findPublicExceptionInCauseChain()
    if (publicException != null) {
      // `toErrorResponse` calls `RequestContext.setExceptionForLog` for us
      return publicException.toErrorResponse(request)
    }

    RequestContext.setExceptionForLog(request, lensFailure)
    return delegate.badRequest(lensFailure)
  }
}
