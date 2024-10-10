package no.liflig.http4k.setup

import org.http4k.core.Request
import org.http4k.core.with
import org.http4k.lens.RequestContextKey

internal val excludeRequestBodyFromLogLens = RequestContextKey.defaulted(contexts, false)

/**
 * Excludes the request body from logs by the
 * [LoggingFilter][no.liflig.http4k.setup.logging.LoggingFilter] for this request. Call this in your
 * handler before returning a response.
 */
fun Request.excludeRequestBodyFromLog() {
  with(excludeRequestBodyFromLogLens of true)
}

internal val excludeResponseBodyFromLogLens = RequestContextKey.defaulted(contexts, false)

/**
 * Excludes the response body from logs by the
 * [LoggingFilter][no.liflig.http4k.setup.logging.LoggingFilter] for this request. Call this in your
 * handler before returning a response.
 */
fun Request.excludeResponseBodyFromLog() {
  with(excludeResponseBodyFromLogLens of true)
}
