package no.liflig.http4k.setup.utils

import org.http4k.core.Request
import org.http4k.lens.BiDiLens
import org.http4k.lens.RequestContextKey

/**
 * Calling a lens constructed with [RequestContextKey] will throw if the request has not passed
 * through an http4k server, since http4k expects an 'x-http4k-context' header on the request which
 * points to the request context to get the value from.
 *
 * But in some cases, we want to support not being in the context of an http4k server request, and
 * have a sensible default to use for the value. An example of this is in
 * [no.liflig.http4k.setup.logging.HttpBodyLog.from], which we expose to library users to allow them
 * to log HTTP request/response bodies in the same manner as our LoggingFilter here. That may be
 * used on outgoing requests, which will not have the 'x-http4k-context' header, causing request
 * context lenses to throw an exception. In these cases, we use this function to default to a value
 * if the request context lens throws, so that we don't require being in the context of an http4k
 * server.
 */
internal fun <T> getFromRequestContext(
    request: Request,
    requestContextLens: BiDiLens<Request, T>,
    default: T
): T {
  try {
    return requestContextLens(request)
  } catch (_: Exception) {
    return default
  }
}
