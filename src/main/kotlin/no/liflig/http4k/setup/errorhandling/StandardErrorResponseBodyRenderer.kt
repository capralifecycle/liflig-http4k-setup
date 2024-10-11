package no.liflig.http4k.setup.errorhandling

import no.liflig.http4k.setup.JsonBodyLensFailure
import org.http4k.contract.ErrorResponseRenderer
import org.http4k.core.ContentType
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.Header
import org.http4k.lens.LensFailure

/**
 * Responsible for converting lens failure to bad request response and providing body in
 * standardized Liflig json-format. See [ErrorResponseBody].
 */
object StandardErrorResponseBodyRenderer : ErrorResponseRenderer {
  override fun badRequest(lensFailure: LensFailure): Response {
    val target = lensFailure.target
    check(target is Request)

    val jsonBodyFailure = lensFailure.cause as? JsonBodyLensFailure
    val errorResponseBody =
        if (jsonBodyFailure != null) {
          ErrorResponseBody(
              title = jsonBodyFailure.errorResponse,
              detail = jsonBodyFailure.responseDetail,
              status = Status.BAD_REQUEST.code,
              instance = target.uri.path,
          )
        } else {
          ErrorResponseBody(
              "Missing/invalid parameters",
              detail = lensFailure.toSimplifiedLensErrorMessage(),
              status = Status.BAD_REQUEST.code,
              instance = target.uri.path,
          )
        }

    return Response(Status.BAD_REQUEST)
        .with(Header.CONTENT_TYPE.of(ContentType.APPLICATION_JSON))
        .with(ErrorResponseBody.bodyLens.of(errorResponseBody))
  }

  private fun LensFailure.toSimplifiedLensErrorMessage(): String =
      failures.joinToString(",") {
        "[${it.meta.location}] parameter [${it.meta.name}] has the following problem: [${it.javaClass.simpleName}]"
      }

  override fun notFound(): Response =
      Response(Status.NOT_FOUND)
          .with(
              ErrorResponseBody.bodyLens of
                  ErrorResponseBody(
                      "No route found on this path. Have you used the correct HTTP verb?",
                      detail = null,
                      status = Status.BAD_REQUEST.code,
                      instance = "unknown",
                  ),
          )
}
