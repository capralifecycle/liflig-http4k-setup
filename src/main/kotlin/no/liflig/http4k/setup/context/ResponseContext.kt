package no.liflig.http4k.setup.context

import java.util.*
import org.http4k.core.Response
import org.http4k.lens.BiDiLens
import org.http4k.lens.Meta
import org.http4k.lens.ParamMeta
import org.http4k.lens.ResponseKey
import org.http4k.lens.ResponseLens
import org.http4k.routing.ResponseWithContext

/**
 * Similar to [RequestContext], but fills the role of attaching context to responses. Unlike
 * `RequestContext`, we don't need to use a mutable object in a `RequestKey` here (see the
 * [RequestContext] docstring for why we do that), since the response already propagates _outwards_
 * in the http4k filter stack (so we can just attach context using [ResponseKey]s).
 *
 * At the time of writing, this class only keeps context about whether a response body is valid
 * JSON. If we in the future want to add more info to the response context, we should consider
 * making this a class like [RequestContext], and gather all response context fields under a single
 * [ResponseKey].
 */
internal object ResponseContext {
  internal fun getValidJsonResponseBody(response: Response): String? {
    return validJsonResponseBody(response)
  }

  internal fun markResponseBodyAsValidJson(response: Response, responseBody: String): Response {
    return validJsonResponseBody.set(response, responseBody)
  }

  private val validJsonResponseBody =
      // Add UUID to response key name, to prevent name collisions
      optionalResponseKey<String>("json-response-body-${UUID.randomUUID()}")
}

/**
 * [org.http4k.lens.RequestKey] offers `required` and `optional` constructors, but [ResponseKey]
 * only offers `of`, which works like `required`. But for keeping valid JSON response bodies, we
 * want to use an _optional_ response key, since this is only set when
 * [no.liflig.http4k.setup.createJsonBodyLens] has been used on the response.
 *
 * The implementation has taken inspiration from [org.http4k.lens.RequestKey.optional].
 */
private fun <T : Any> optionalResponseKey(name: String): ResponseLens<T?> {
  val meta =
      Meta(
          required = false,
          location = "context",
          paramMeta = ParamMeta.ObjectParam,
          name = name,
          description = null,
          metadata = emptyMap(),
      )
  val get: (Response) -> T? = { target ->
    @Suppress("UNCHECKED_CAST")
    when (target) {
      is ResponseWithContext -> target.context[name] as? T
      else -> null
    }
  }
  val setter: (T?, Response) -> Response = { value, target ->
    if (value != null) {
      when (target) {
        is ResponseWithContext ->
            ResponseWithContext(target.delegate, target.context + (name to value))
        else -> ResponseWithContext(target, mapOf(name to value))
      }
    } else {
      target
    }
  }
  return BiDiLens(meta, get, setter)
}
