package no.liflig.http4k.setup

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
 * standardized json-format.
 */
object LifligJsonErrorResponseRenderer : ErrorResponseRenderer {
  override fun badRequest(lensFailure: LensFailure): Response {
    val target = lensFailure.target
    check(target is Request)

    return Response(Status.BAD_REQUEST)
        .with(Header.CONTENT_TYPE of ContentType.APPLICATION_JSON)
        .with(
            ErrorResponseBody.bodyLens of
                ErrorResponseBody(
                    "Missing/invalid parameters",
                    detail = lensFailure.toSimplifiedLensErrorMessage(),
                    status = Status.BAD_REQUEST.code,
                    instance = target.uri.path,
                ),
        )
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
