@file:Suppress("IfThenToSafeAccess") // Clearer to use if-statement in this case than Elvis operator

package no.liflig.http4k.setup.context

import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import no.liflig.http4k.setup.context.RequestContext.Companion.readRequestContext
import no.liflig.http4k.setup.context.RequestContext.Companion.updateRequestContext
import no.liflig.logging.LogLevel
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.lens.RequestKey

/**
 * We have some state that we want to attach to requests, for various use-cases:
 * - Excluding request/response bodies from logs on a per-request basis
 * - Marking a request body as valid JSON, to avoid re-parsing it for body logging
 * - Attaching an exception to the request log
 *
 * To support this, we attach a context object to each request with an http4k [RequestKey]. We
 * instantiate this context object in [RequestContextFilter], which we add to our filter stack in
 * [LifligBasicApiSetup.create][no.liflig.http4k.setup.LifligBasicApiSetup.create]. The methods on
 * [RequestContext.Companion] get the context from the given request using the `RequestKey`,
 * allowing the caller to read and update these fields on the request's context.
 *
 * The reason that we use a mutable object for this context, instead of a [RequestKey] for each
 * field, is that we want to propagate this state _outwards_ in the http4k filter stack. For
 * example, when an endpoint calls `Request.excludeRequestBodyFromLog`, we want our `LoggingFilter`
 * to be able to observe that. Adding a [RequestKey] to a request copies the request object and adds
 * the key to the copy - our `LoggingFilter` would then still see the previous request object
 * without the key. By instead adding an outer [RequestContextFilter], modifications to this context
 * object inside an endpoint will be propagated through the filter stack.
 *
 * We synchronize all reads and writes to context fields behind a [lock] (used in
 * [readRequestContext]/[updateRequestContext]). This is for the case where sub-threads are spawned
 * while processing a request, which may cause different threads to read one request's context.
 */
internal class RequestContext {
  private val lock = ReentrantLock()

  /** See [no.liflig.http4k.setup.errorResponse]. */
  private var exceptionForLog: Throwable? = null

  /** See [no.liflig.http4k.setup.includeRequestBodyInLog]. */
  private var includeRequestBodyInLog: Boolean = false
  /** See [no.liflig.http4k.setup.includeResponseBodyInLog]. */
  private var includeResponseBodyInLog: Boolean = false

  /** See [no.liflig.http4k.setup.excludeRequestBodyFromLog]. */
  private var excludeRequestBodyFromLog: Boolean = false
  /** See [no.liflig.http4k.setup.excludeResponseBodyFromLog]. */
  private var excludeResponseBodyFromLog: Boolean = false

  private var validJsonRequestBody: String? = null

  private var requestLogLevel: LogLevel? = null

  internal companion object {
    internal fun getExceptionForLog(request: Request): Throwable? {
      return readRequestContext(request, defaultValue = null) { it.exceptionForLog }
    }

    internal fun isRequestBodyIncludedInLog(request: Request): Boolean {
      return readRequestContext(request, defaultValue = false) { it.includeRequestBodyInLog }
    }

    internal fun isResponseBodyIncludedInLog(request: Request): Boolean {
      return readRequestContext(request, defaultValue = false) { it.includeResponseBodyInLog }
    }

    internal fun isRequestBodyExcludedFromLog(request: Request): Boolean {
      return readRequestContext(request, defaultValue = false) { it.excludeRequestBodyFromLog }
    }

    internal fun isResponseBodyExcludedFromLog(request: Request): Boolean {
      return readRequestContext(request, defaultValue = false) { it.excludeResponseBodyFromLog }
    }

    internal fun getValidJsonRequestBody(request: Request): String? {
      return readRequestContext(request, defaultValue = null) { it.validJsonRequestBody }
    }

    internal fun getRequestLogLevel(request: Request): LogLevel? {
      return readRequestContext(request, defaultValue = null) { it.requestLogLevel }
    }

    internal fun setExceptionForLog(request: Request, exception: Throwable) {
      updateRequestContext(request) { it.exceptionForLog = exception }
    }

    internal fun includeRequestBodyInLog(request: Request) {
      updateRequestContext(request) { it.includeRequestBodyInLog = true }
    }

    internal fun includeResponseBodyInLog(request: Request) {
      updateRequestContext(request) { it.includeResponseBodyInLog = true }
    }

    internal fun excludeRequestBodyFromLog(request: Request) {
      updateRequestContext(request) { it.excludeRequestBodyFromLog = true }
    }

    internal fun excludeResponseBodyFromLog(request: Request) {
      updateRequestContext(request) { it.excludeResponseBodyFromLog = true }
    }

    internal fun markRequestBodyAsValidJson(request: Request, requestBody: String) {
      updateRequestContext(request) { it.validJsonRequestBody = requestBody }
    }

    internal fun setRequestLogLevel(request: Request, level: LogLevel) {
      updateRequestContext(request) { it.requestLogLevel = level }
    }

    internal val lens =
        RequestKey.optional<RequestContext>(
            // Add UUID to request key name, to prevent name collisions
            "request-context-${UUID.randomUUID()}",
        )

    /**
     * @param defaultValue If no context was found for the given request, this default value is
     *   returned instead of calling [consumer]. This may happen if the given request is not an
     *   incoming http4k server request - for example, if it's an outgoing request that we're
     *   sending from an HTTP client, which would not have passed through our
     *   [RequestContextFilter].
     * @param consumer Called on the request context, if a context was found for the given request.
     *   The context [lock] is acquired before this is called, and released after.
     */
    private inline fun <ReturnT> readRequestContext(
        request: Request,
        defaultValue: ReturnT,
        consumer: (RequestContext) -> ReturnT
    ): ReturnT {
      val context = lens(request)
      return if (context != null) {
        context.lock.withLock { consumer(context) }
      } else {
        defaultValue
      }
    }

    /**
     * @param updater Called on the request context, if a context was found for the given request.
     *   The context [lock] is acquired before this is called, and released after.
     */
    private inline fun updateRequestContext(request: Request, updater: (RequestContext) -> Unit) {
      val context = lens(request)
      if (context != null) {
        context.lock.withLock { updater(context) }
      }
    }
  }
}

internal class RequestContextFilter : Filter {
  override fun invoke(nextHandler: HttpHandler): HttpHandler {
    return { request ->
      val requestWithContext = RequestContext.lens.inject(RequestContext(), request)
      nextHandler(requestWithContext)
    }
  }
}
