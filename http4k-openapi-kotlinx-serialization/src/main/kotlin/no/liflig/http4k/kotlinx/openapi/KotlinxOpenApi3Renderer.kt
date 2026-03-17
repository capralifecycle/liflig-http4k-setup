package no.liflig.http4k.kotlinx.openapi

import no.liflig.http4k.kotlinx.jsonschema.KotlinxSerializationJsonSchemaCreator
import org.http4k.contract.jsonschema.JsonSchema
import org.http4k.contract.jsonschema.v3.JsonToJsonSchema
import org.http4k.contract.openapi.ApiRenderer
import org.http4k.contract.openapi.v3.Api
import org.http4k.contract.openapi.v3.OpenApi3ApiRenderer
import org.http4k.format.Json
import org.http4k.format.JsonType

/**
 * [ApiRenderer] that combines http4k's manual JSON rendering for the OpenAPI document structure
 * with kotlinx.serialization-based JSON Schema generation for DTO models.
 *
 * The [toSchema] fallback chain mirrors [ApiRenderer.Auto]:
 * 1. Try [JsonToJsonSchema] for values that are already [NODE] (raw JSON bodies)
 * 2. Fall back to [KotlinxSerializationJsonSchemaCreator] for `@Serializable` DTOs
 * 3. Fall back to reflection-based enum schema for Java Enum constants
 *
 * Use this with [org.http4k.contract.openapi.v3.OpenApi3]'s primary constructor to render OpenAPI
 * documents without Jackson:
 * ```kotlin
 * val renderer = KotlinxOpenApi3Renderer(json = KotlinxSerialization, schema = schema)
 * OpenApi3(apiInfo = apiInfo, json = KotlinxSerialization, apiRenderer = renderer)
 * ```
 *
 * Passing `apiRenderer` as a named parameter forces Kotlin to use the `OpenApi3` primary
 * constructor (which takes `Json<NODE>`), because the secondary constructor (which takes
 * `AutoMarshallingJson<NODE>` and uses `ApiRenderer.Auto`) does not have an `apiRenderer`
 * parameter.
 */
class KotlinxOpenApi3Renderer<NODE : Any>(
    private val json: Json<NODE>,
    private val schema: KotlinxSerializationJsonSchemaCreator<NODE>,
    private val refLocationPrefix: String = "components/schemas",
) : ApiRenderer<Api<NODE>, NODE> {

  private val delegate = OpenApi3ApiRenderer(json, refLocationPrefix)
  private val jsonToJsonSchema = JsonToJsonSchema(json, refLocationPrefix)

  override fun api(api: Api<NODE>): NODE = stripNullValues(delegate.api(api))

  override fun toSchema(
      obj: Any,
      overrideDefinitionId: String?,
      refModelNamePrefix: String?,
  ): JsonSchema<NODE> {
    // 1. Try JsonToJsonSchema for values that are already NODE (raw JSON bodies).
    try {
      @Suppress("UNCHECKED_CAST")
      return jsonToJsonSchema.toSchema(obj as NODE, overrideDefinitionId, refModelNamePrefix)
    } catch (_: ClassCastException) {
      // Not a NODE — fall through to kotlinx.serialization path.
    }

    // 2. Try KotlinxSerializationJsonSchemaCreator for @Serializable DTOs.
    val result = schema.toSchema(obj, overrideDefinitionId, refModelNamePrefix)

    // 3. If the schema creator returned an empty schema and the object is a Java Enum,
    // fall back to reflection-based enum schema generation.
    // This handles the case where OpenApi3 passes Java Enum constants for query/path
    // parameter schemas (e.g. paramMeta.clz.java.enumConstants[0]).
    if (isEmptySchema(result) && obj is Enum<*>) {
      return toEnumSchema(obj, refModelNamePrefix, overrideDefinitionId)
    }

    return result
  }

  private fun isEmptySchema(schema: JsonSchema<NODE>): Boolean =
      schema.definitions.isEmpty() && json.fields(schema.node).none()

  /**
   * Recursively strips null values from JSON objects. http4k's [OpenApi3ApiRenderer] emits
   * `"description": null` for unset fields, which is invalid in the OpenAPI spec.
   */
  private fun stripNullValues(node: NODE): NODE =
      when (json.typeOf(node)) {
        JsonType.Object ->
            json.obj(
                json.fields(node).mapNotNull { (key, value) ->
                  if (json.typeOf(value) == JsonType.Null) null else key to stripNullValues(value)
                },
            )
        JsonType.Array -> json.array(json.elements(node).map { stripNullValues(it) })
        else -> node
      }

  private fun toEnumSchema(
      obj: Enum<*>,
      refModelNamePrefix: String?,
      overrideDefinitionId: String?,
  ): JsonSchema<NODE> {
    val newDefinition =
        json.obj(
            "example" to json.string(obj.name),
            "type" to json.string("string"),
            "enum" to json.array(obj.javaClass.enumConstants.map { json.string(it.name) }),
        )
    val definitionId =
        (refModelNamePrefix.orEmpty()) +
            (overrideDefinitionId ?: ("object" + newDefinition.hashCode()))
    return JsonSchema(
        json { obj("\$ref" to string("#/$refLocationPrefix/$definitionId")) },
        mapOf(definitionId to newDefinition),
    )
  }
}
