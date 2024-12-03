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
          val params = request.queries(paramName).filterNotNull()
          when (params.size) {
            // If we receive no query params, return empty list to let `required`/`optional` handle
            // this case
            0 -> emptyList()
            // If we receive a single query param, we parse it as a comma-separated list
            1 -> {
              val param = params.first()
              // If the client sent a query parameter with a name but no value, i.e. '&param=', we
              // get `params` as a list with one empty string - we treat this as an empty list.
              if (param == "") {
                listOf(emptyList())
              } else {
                listOf(param.split(","))
              }
            }
            // If we receive multiple query params, we assume the client sends list query params as
            // separate
            // params
            else -> listOf(params)
          }
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

  /** Works like http4k's `Query.enum`, but for lists of enum query parameters. */
  inline fun <reified EnumT : Enum<EnumT>> enum(): BiDiLensSpec<Request, List<EnumT>> {
    return this.mapWithNewMeta(
        nextIn = { params -> params.map { param -> enumValueOf<EnumT>(param) } },
        nextOut = { enumValues -> enumValues.map { it.name } },
        paramMeta = ParamMeta.ArrayParam(ParamMeta.EnumParam(EnumT::class)),
    )
  }
}
