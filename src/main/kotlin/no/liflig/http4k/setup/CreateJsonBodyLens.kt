package no.liflig.http4k.setup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.http4k.core.ContentType
import org.http4k.core.Request
import org.http4k.core.with
import org.http4k.lens.BiDiBodyLens
import org.http4k.lens.BiDiBodyLensSpec
import org.http4k.lens.Header
import org.http4k.lens.LensGet
import org.http4k.lens.LensSet
import org.http4k.lens.Meta
import org.http4k.lens.ParamMeta
import org.http4k.lens.RequestContextKey

private val httpBodyJson = Json {
  encodeDefaults = true
  ignoreUnknownKeys = true
}

/**
 * Creates an http4k body lens to get/set a JSON body on an HTTP request or response, using
 * `kotlinx.serialization` for JSON serialization.
 *
 * ### Usage
 *
 * You'll typically define a body lens on the companion object of a DTO (Data Transfer Object):
 * ```
 * @Serializable
 * data class ExampleDto(val name: String) {
 *   companion object {
 *      val bodyLens = createJsonBodyLens(serializer()) // serializer generated by @Serializable
 *   }
 * }
 * ```
 *
 * Then you can use it in your HTTP handler:
 * ```
 * fun handler(request: Request): Response {
 *   val requestBody = ExampleDto.bodyLens(request)
 *
 *   val responseBody = ExampleDto(name = "example")
 *   return Response(Status.OK).with(ExampleDto.bodyLens.of(responseBody))
 * }
 * ```
 *
 * @param serializer The serializer for the type you want to extract from the body (annotate the
 *   type with `@Serializable` from `kotlinx.serialization` to generate a serializer for it).
 * @param mapDecodingException Pass a function here if you want to map JSON decoding exceptions to
 *   some other exception for this type (e.g. a wrapper exception to provide more context). If you
 *   do this, remember to set the original exception as the new exception's `cause`, so you don't
 *   lose context!
 * @param jsonInstance The [kotlinx.serialization.json.Json] instance to use for serialization.
 *   Defaults to a Json instance with `encodeDefaults = true` and `ignoreUnknownKeys = true`.
 */
fun <T> createJsonBodyLens(
    serializer: KSerializer<T>,
    mapDecodingException: (Exception) -> Exception = { e -> e }, // Default no-op
    jsonInstance: Json = httpBodyJson,
): BiDiBodyLens<T> =
    BiDiBodyLensSpec(
            contentType = ContentType.APPLICATION_JSON,
            get =
                LensGet { _, httpMessage ->
                  val jsonBody =
                      try {
                        jsonInstance.decodeFromString(serializer, httpMessage.bodyString())
                      } catch (e: Exception) {
                        throw mapDecodingException(e)
                      }

                  /** See [bodyIsValidJson]. */
                  if (httpMessage is Request) {
                    httpMessage.bodyIsValidJson()
                  }

                  listOf(jsonBody)
                },
            set =
                LensSet { _, bodies, previousHttpMessage ->
                  var nextHttpMessage =
                      previousHttpMessage.with(Header.CONTENT_TYPE of ContentType.APPLICATION_JSON)

                  val body = bodies.getOrNull(0)
                  if (body != null) {
                    val jsonBody = jsonInstance.encodeToString(serializer, body)
                    nextHttpMessage = nextHttpMessage.body(jsonBody)
                  }

                  nextHttpMessage
                },
            /** Copied from [org.http4k.lens.string]. */
            metas =
                listOf(
                    Meta(
                        required = true,
                        location = "body",
                        paramMeta = ParamMeta.StringParam,
                        name = "body",
                        description = null,
                        metadata = emptyMap(),
                    ),
                ),
        )
        .toLens()

/**
 * In our [LoggingFilter][no.liflig.http4k.setup.logging.LoggingFilter], we want to log the body as
 * plain JSON in the request/response body log. We want to avoid having to re-parse the body as JSON
 * there, as that has typically already been done in the HTTP handler, and we don't want to parse it
 * twice.
 *
 * However, we can only do this optimization if we trust that the body is valid JSON, otherwise it
 * can break our log output.
 * - In the case of responses, we trust that our server generates valid JSON.
 * - In the case of requests, we can't necessarily trust that the client has sent valid JSON, unless
 *   we've already parsed it. That's where this function comes in:
 *     - If we use [createJsonBodyLens] for parsing, we can call this after parsing succeeds, as we
 *       then know that the body is valid JSON.
 *     - In the rare case that you can't use [createJsonBodyLens], but you _know_ that the request
 *       body has been parsed as valid JSON some other way, you can call this function yourself.
 *
 * The function uses a [RequestContextKey] to mark the request as having a valid JSON body, which we
 * can use in our LoggingFilter to know that we don't have to re-parse the body.
 */
fun Request.bodyIsValidJson() {
  this.with(requestJsonBodyLens.of(true))
}

internal val requestJsonBodyLens = RequestContextKey.defaulted(contexts, false)
