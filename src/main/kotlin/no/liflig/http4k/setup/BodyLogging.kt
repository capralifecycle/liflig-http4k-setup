package no.liflig.http4k.setup

import no.liflig.http4k.setup.context.RequestContext
import org.http4k.core.Request

/**
 * Includes the request body in logs by the
 * [LoggingFilter][no.liflig.http4k.setup.logging.LoggingFilter] for this request.
 *
 * This can be useful when you've set [LifligBasicApiSetup.logHttpBody] to `false` (which is the
 * default), but you want to override that for a specific endpoint.
 *
 * ### Example
 *
 * Call this in your handler before returning a response:
 * ```
 * fun handler(request: Request): Response {
 *   request.includeRequestBodyInLog()
 *
 *   // ...
 * }
 * ```
 */
fun Request.includeRequestBodyInLog() {
  RequestContext.includeRequestBodyInLog(this)
}

/**
 * Includes the response body in logs by the
 * [LoggingFilter][no.liflig.http4k.setup.logging.LoggingFilter] for this request.
 *
 * This can be useful when you've set [LifligBasicApiSetup.logHttpBody] to `false` (which is the
 * default), but you want to override that for a specific endpoint.
 *
 * ### Example
 *
 * Call this in your handler before returning a response:
 * ```
 * fun handler(request: Request): Response {
 *   request.includeRequestBodyInLog()
 *
 *   // ...
 * }
 * ```
 */
fun Request.includeResponseBodyInLog() {
  RequestContext.includeResponseBodyInLog(this)
}

/**
 * Includes the request and response bodies in logs by the
 * [LoggingFilter][no.liflig.http4k.setup.logging.LoggingFilter] for this request.
 *
 * This can be useful when you've set [LifligBasicApiSetup.logHttpBody] to `false` (which is the
 * default), but you want to override that for a specific endpoint.
 *
 * ### Example
 *
 * Call this in your handler before returning a response:
 * ```
 * fun handler(request: Request): Response {
 *   request.includeRequestAndResponseBodyInLog()
 *
 *   // ...
 * }
 * ```
 */
fun Request.includeRequestAndResponseBodyInLog() {
  RequestContext.includeRequestBodyInLog(this)
  RequestContext.includeResponseBodyInLog(this)
}

/**
 * Excludes the request body from logs by the
 * [LoggingFilter][no.liflig.http4k.setup.logging.LoggingFilter] for this request.
 *
 * This can be useful when you've set [LifligBasicApiSetup.logHttpBody] to `true`, but you want to
 * override that for a specific endpoint.
 *
 * ### Example
 *
 * Call this in your handler before returning a response:
 * ```
 * fun handler(request: Request): Response {
 *   request.excludeRequestBodyFromLog()
 *
 *   // ...
 * }
 * ```
 */
fun Request.excludeRequestBodyFromLog() {
  RequestContext.excludeRequestBodyFromLog(this)
}

/**
 * Excludes the response body from logs by the
 * [LoggingFilter][no.liflig.http4k.setup.logging.LoggingFilter] for this request.
 *
 * This can be useful when you've set [LifligBasicApiSetup.logHttpBody] to `true`, but you want to
 * override that for a specific endpoint.
 *
 * ### Example
 *
 * Call this in your handler before returning a response:
 * ```
 * fun handler(request: Request): Response {
 *   request.excludeResponseBodyFromLog()
 *
 *   // ...
 * }
 * ```
 */
fun Request.excludeResponseBodyFromLog() {
  RequestContext.excludeResponseBodyFromLog(this)
}

/**
 * Excludes the request and response bodies from logs by the
 * [LoggingFilter][no.liflig.http4k.setup.logging.LoggingFilter] for this request.
 *
 * This can be useful when you've set [LifligBasicApiSetup.logHttpBody] to `true`, but you want to
 * override that for a specific endpoint.
 *
 * ### Example
 *
 * Call this in your handler before returning a response:
 * ```
 * fun handler(request: Request): Response {
 *   request.excludeRequestAndResponseBodyFromLog()
 *
 *   // ...
 * }
 * ```
 */
fun Request.excludeRequestAndResponseBodyFromLog() {
  RequestContext.excludeRequestBodyFromLog(this)
  RequestContext.excludeResponseBodyFromLog(this)
}
