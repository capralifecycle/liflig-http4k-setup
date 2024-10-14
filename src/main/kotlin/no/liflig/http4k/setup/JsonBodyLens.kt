package no.liflig.http4k.setup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.http4k.core.ContentType
import org.http4k.core.HttpMessage
import org.http4k.core.Request
import org.http4k.core.with
import org.http4k.lens.BiDiBodyLens
import org.http4k.lens.Header
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
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
 * @param errorResponse If parsing fails, an http4k [LensFailure] is thrown and caught by our
 *   [ErrorResponseRenderer][no.liflig.http4k.setup.errorhandling.StandardErrorResponseBodyRenderer],
 *   which maps it to a 400 Bad Request response. The default error message is quite generic, so you
 *   can pass a custom one here to provide more info to the user (the message will be in the `title`
 *   field of the [ErrorResponseBody][no.liflig.http4k.setup.errorhandling.ErrorResponseBody]).
 * @param errorResponseDetail See [errorResponse]. This optional parameter sets the `detail` field
 *   on the [ErrorResponseBody][no.liflig.http4k.setup.errorhandling.ErrorResponseBody] if parsing
 *   fails.
 * @param includeExceptionMessageInErrorResponse See [errorResponse]. Enable this optional parameter
 *   to include the message from the JSON decoding exception on the `detail` field of the
 *   [ErrorResponseBody][no.liflig.http4k.setup.errorhandling.ErrorResponseBody] when parsing fails.
 *   This exception message can provide useful context to the user about why the request failed, but
 *   we do not expose it by default.
 *
 *   If combined with [errorResponseDetail], the exception message will be in parentheses after the
 *   detail message.
 *
 * @param jsonInstance The [kotlinx.serialization.json.Json] instance to use for serialization.
 *   Defaults to a Json instance with `encodeDefaults = true` and `ignoreUnknownKeys = true`.
 */
fun <T> createJsonBodyLens(
    serializer: KSerializer<T>,
    errorResponse: String = "Failed to parse request body",
    errorResponseDetail: String? = null,
    includeExceptionMessageInErrorResponse: Boolean = false,
    jsonInstance: Json = httpBodyJson,
): BiDiBodyLens<T> =
    BiDiBodyLens(
        metas = jsonBodyLensMetas,
        contentType = ContentType.APPLICATION_JSON,
        get =
            fun(httpMessage: HttpMessage): T {
              val jsonBody =
                  try {
                    jsonInstance.decodeFromString(serializer, httpMessage.bodyString())
                  } catch (e: Exception) {
                    /**
                     * Will be caught by [org.http4k.filter.ServerFilters.CatchLensFailure] and
                     * passed to our
                     * [no.liflig.http4k.setup.errorhandling.StandardErrorResponseBodyRenderer],
                     * which maps the exception to a 400 Bad Request response.
                     */
                    throw LensFailure(
                        failures = jsonBodyLensMetas.map(::Invalid),
                        cause =
                            JsonBodyLensFailure(
                                errorResponse,
                                errorResponseDetail,
                                includeExceptionMessageInErrorResponse,
                                cause = e,
                            ),
                        target = httpMessage,
                        message = e.message ?: "<unknown>",
                    )
                  }

              /** See [markBodyAsValidJson]. */
              if (httpMessage is Request) {
                httpMessage.markBodyAsValidJson()
              }

              return jsonBody
            },
        setLens =
            fun(jsonBody: T, httpMessage: HttpMessage): HttpMessage {
              return httpMessage
                  .with(Header.CONTENT_TYPE of ContentType.APPLICATION_JSON)
                  .body(jsonInstance.encodeToString(serializer, jsonBody))
            },
    )

internal class JsonBodyLensFailure(
    val errorResponse: String,
    errorResponseDetail: String?,
    includeExceptionMessageInErrorResponse: Boolean,
    override val cause: Exception,
) : Exception() {
  override val message =
      errorResponse + (if (errorResponseDetail != null) "(${errorResponseDetail})" else "")

  val responseDetail: String? =
      when {
        errorResponseDetail != null -> {
          if (includeExceptionMessageInErrorResponse && cause.message != null) {
            "${errorResponseDetail} (${cause.message})"
          } else {
            errorResponseDetail
          }
        }
        includeExceptionMessageInErrorResponse -> cause.message
        else -> null
      }
}

/** Copied from [org.http4k.lens.string]. */
private val jsonBodyLensMetas =
    listOf(
        Meta(
            required = true,
            location = "body",
            paramMeta = ParamMeta.StringParam,
            name = "body",
            description = null,
            metadata = emptyMap(),
        ),
    )

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
fun Request.markBodyAsValidJson() {
  try {
    this.with(requestBodyIsValidJson.of(true))
  } catch (_: Exception) {
    // This can throw if there is no 'x-http4k-context' header present on the request. Since we only
    // call this as an optimization (to avoid re-parsing JSON in LoggingFilter), we never want to
    // fail on this call, so we catch all exceptions. We test that it works in
    // CreateJsonBodyLensTest.
  }
}

internal val requestBodyIsValidJson = RequestContextKey.defaulted(contexts, false)
