package no.liflig.http4k.setup

import no.liflig.http4k.setup.context.RequestContext
import org.http4k.core.Request

/**
 * Excludes the request body from logs by the
 * [LoggingFilter][no.liflig.http4k.setup.logging.LoggingFilter] for this request. Call this in your
 * handler before returning a response.
 */
fun Request.excludeRequestBodyFromLog() {
  RequestContext.excludeRequestBodyFromLog(this)
}

/**
 * Excludes the response body from logs by the
 * [LoggingFilter][no.liflig.http4k.setup.logging.LoggingFilter] for this request. Call this in your
 * handler before returning a response.
 */
fun Request.excludeResponseBodyFromLog() {
  RequestContext.excludeResponseBodyFromLog(this)
}
