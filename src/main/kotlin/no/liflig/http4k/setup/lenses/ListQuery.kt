package no.liflig.http4k.setup.lenses

import org.http4k.core.Request
import org.http4k.lens.BiDiLensSpec
import org.http4k.lens.LensGet
import org.http4k.lens.LensSet
import org.http4k.lens.LensSpec
import org.http4k.lens.ParamMeta

/**
 * There is no defined standard for passing multiple values for a query parameter in a URL. Web
 * servers typically allow one or both of these formats:
 * - `https://example.com?queryParam=value1&queryParam=value2`
 * - `https://example.com?queryParam=value1,value2`
 *
 * http4k (which we use on the backend) expects list query params on the first format (when using
 * `Query.multi`), but RTK Query (which we use on the frontend) passes list query params on the
 * second format. Thus, when we pass `queryParam` from an RTK Query client to an http4k server,
 * http4k sees it as a single parameter with value `"value1,value2"`.
 *
 * This lens works like [org.http4k.lens.Query], except it accepts query params on both the first
 * and the second format, i.e. multiple query params with the same key or a single query param with
 * comma-separated values.
 */
object ListQuery :
    BiDiLensSpec<Request, List<String>>(
        location = "query",
        ParamMeta.ArrayParam(ParamMeta.StringParam),
        LensGet { paramName, request ->
          var params = request.queries(paramName).filterNotNull()
          // If we receive a single query param, we parse it as a comma-separated list. But if we
          // receive multiple, we assume that clients don't mix multiple params with comma-separated
          // values, i.e. `queryParam=value1&queryParam=value2,value3`. In this case, the comma in
          // the second param is more likely part of the value, so we don't split it.
          if (params.size == 1) {
            params = params.first().split(",")
          }
          listOf(params)
        },
        LensSet { paramName, params, request ->
          val requestWithoutQuery = request.removeQuery(paramName)
          requestWithoutQuery.query(paramName, params.asSequence().flatten().joinToString(","))
        },
    ) {
  /**
   * Works like [LensSpec.map], except the mapper function applies to each value of the query param,
   * so you don't have to do `ListQuery.map { values -> values.map { ... } }` yourself.
   */
  fun <T> mapValues(mapper: (String) -> T): LensSpec<Request, List<T>> {
    return this.map { values -> values.map(mapper) }
  }

  /**
   * Works like [BiDiLensSpec.map], except the mapper functions apply to each value of the query
   * param, so you don't have to do `ListQuery.map({ values -> values.map { ... } }, { values ->
   * values.map { ... } })` yourself.
   */
  fun <T> mapValues(
      incoming: (String) -> T,
      outgoing: (T) -> String
  ): BiDiLensSpec<Request, List<T>> {
    return this.map(
        { incomingValues -> incomingValues.map(incoming) },
        { outgoingValues -> outgoingValues.map(outgoing) },
    )
  }
}
