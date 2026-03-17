package no.liflig.http4k.kotlinx.jsonschema

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.starProjectedType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import org.http4k.contract.jsonschema.JsonSchema
import org.http4k.contract.jsonschema.JsonSchemaCreator
import org.http4k.format.AutoMarshallingJson

@OptIn(ExperimentalSerializationApi::class)
class KotlinxSerializationJsonSchemaCreator<NODE : Any>(
    private val json: AutoMarshallingJson<NODE>,
    private val kotlinxJson: kotlinx.serialization.json.Json,
    private val refLocationPrefix: String = "components/schemas",
    private val sealedClassExampleProvider: SealedClassExampleProvider =
        DefaultSealedClassExampleProvider(),
    private val formatMappings: Map<String, String> = emptyMap(),
    private val nullableStrategy: NullableStrategy = NullableStrategy.TYPE_ARRAY,
) : JsonSchemaCreator<Any, NODE> {

  companion object {
    /** Common format mappings for well-known types serialized as strings. */
    val COMMON_FORMAT_MAPPINGS =
        mapOf(
            "Instant" to "date-time",
            "LocalDate" to "date",
            "LocalDateTime" to "date-time",
            "ZonedDateTime" to "date-time",
            "UUID" to "uuid",
            "URI" to "uri",
        )
  }

  private class DefinitionAccumulator<NODE>(
      val schemas: MutableMap<String, NODE> = mutableMapOf(),
      val serialNames: MutableMap<String, String> = mutableMapOf(),
  )

  override fun toSchema(
      obj: Any,
      overrideDefinitionId: String?,
      refModelNamePrefix: String?,
  ): JsonSchema<NODE> {
    val (serializer, jsonElement) =
        try {
          resolveSerializerAndEncode(obj)
        } catch (_: kotlinx.serialization.SerializationException) {
          // http4k passes `object {}` as a sentinel value in exampleSchemaIsValid.
          // Return an empty schema so the comparison works correctly.
          return JsonSchema(json.obj(), emptyMap())
        }
    val descriptor = serializer.descriptor

    val defs = DefinitionAccumulator<NODE>()
    val visited = mutableSetOf<String>()

    val rootKType =
        try {
          obj::class.starProjectedType
        } catch (_: Exception) {
          null
        }

    val node =
        descriptorToSchema(
            descriptor = descriptor,
            jsonElement = jsonElement,
            defs = defs,
            visited = visited,
            refModelNamePrefix = refModelNamePrefix,
            kType = rootKType,
        )

    if (overrideDefinitionId != null) {
      return applyOverrideDefinitionId(node, defs, overrideDefinitionId, refModelNamePrefix)
    }

    return JsonSchema(node, defs.schemas)
  }

  private fun applyOverrideDefinitionId(
      node: NODE,
      defs: DefinitionAccumulator<NODE>,
      overrideDefinitionId: String,
      refModelNamePrefix: String?,
  ): JsonSchema<NODE> {
    val refField = json.fields(node).firstOrNull { (k, _) -> k == "\$ref" }
    if (refField == null) {
      // Inline schema (primitives, arrays, maps) — no definition to rename.
      return JsonSchema(node, defs.schemas)
    }

    val originalRefPath = json.text(refField.second)
    val originalDefKey = originalRefPath.removePrefix("#/$refLocationPrefix/")
    val newDefKey = (refModelNamePrefix ?: "") + overrideDefinitionId

    if (originalDefKey != newDefKey) {
      val defValue = defs.schemas.remove(originalDefKey)
      if (defValue != null) {
        defs.schemas[newDefKey] = defValue
      }
      defs.serialNames.remove(originalDefKey)
      val newRefPath = "#/$refLocationPrefix/$newDefKey"
      return JsonSchema(json.obj("\$ref" to json.string(newRefPath)), defs.schemas)
    }

    return JsonSchema(node, defs.schemas)
  }

  @Suppress("UNCHECKED_CAST")
  private fun resolveSerializerAndEncode(obj: Any): Pair<KSerializer<Any>, JsonElement> {
    return when (obj) {
      is Collection<*> -> {
        val firstElement =
            obj.firstOrNull()
                ?: throw IllegalArgumentException("Cannot generate schema from empty collection")
        val elementSerializer = kotlinxJson.serializersModule.serializer(firstElement::class.java)
        val listSerializer = ListSerializer(elementSerializer) as KSerializer<Any>
        listSerializer to kotlinxJson.encodeToJsonElement(listSerializer, obj)
      }
      is Map<*, *> -> {
        val firstEntry =
            obj.entries.firstOrNull()
                ?: throw IllegalArgumentException("Cannot generate schema from empty map")
        val keySerializer = kotlinxJson.serializersModule.serializer(firstEntry.key!!::class.java)
        val valueSerializer =
            kotlinxJson.serializersModule.serializer(firstEntry.value!!::class.java)
        val mapSerializer = MapSerializer(keySerializer, valueSerializer) as KSerializer<Any>
        mapSerializer to kotlinxJson.encodeToJsonElement(mapSerializer, obj)
      }
      else -> {
        val serializer =
            kotlinxJson.serializersModule.serializer(obj::class.java) as KSerializer<Any>
        serializer to kotlinxJson.encodeToJsonElement(serializer, obj)
      }
    }
  }

  /**
   * @param kType Optional Kotlin type corresponding to this descriptor, threaded through the
   *   traversal to recover the declared KClass when `descriptor.serialName` is not a loadable class
   *   name (e.g. sealed parents with `@SerialName`). Also used to resolve generic type arguments
   *   for List/Map/Set element types and inline value class inner types.
   */
  private fun descriptorToSchema(
      descriptor: SerialDescriptor,
      jsonElement: JsonElement?,
      defs: DefinitionAccumulator<NODE>,
      visited: MutableSet<String>,
      refModelNamePrefix: String?,
      kType: KType? = null,
  ): NODE {
    // Nullable descriptors have "?" appended to serialName by kotlinx.serialization.
    // Normalize once here so individual handlers don't need to strip it.
    val serialName = descriptor.serialName.removeSuffix("?")

    val baseSchema =
        when (descriptor.kind) {
          is PrimitiveKind ->
              primitiveToSchema(
                  descriptor.kind as PrimitiveKind,
                  serialName,
                  jsonElement,
              )
          SerialKind.ENUM -> enumToSchema(descriptor, serialName, defs, refModelNamePrefix)
          SerialKind.CONTEXTUAL -> {
            val contextualDescriptor =
                kotlinxJson.serializersModule.getContextualDescriptor(descriptor)
                    ?: throw IllegalArgumentException(
                        "Unregistered contextual type: ${descriptor.serialName}"
                    )
            descriptorToSchema(
                contextualDescriptor,
                jsonElement,
                defs,
                visited,
                refModelNamePrefix,
                kType = kType,
            )
          }
          StructureKind.CLASS ->
              if (descriptor.isInline) {
                val innerKType = resolveInlineInnerType(kType)
                descriptorToSchema(
                    descriptor.getElementDescriptor(0),
                    jsonElement,
                    defs,
                    visited,
                    refModelNamePrefix,
                    kType = innerKType,
                )
              } else {
                classToSchema(
                    descriptor,
                    serialName,
                    jsonElement,
                    defs,
                    visited,
                    refModelNamePrefix,
                    kType,
                )
              }
          StructureKind.OBJECT -> json.obj("type" to json.string("object"))
          StructureKind.LIST ->
              listToSchema(descriptor, jsonElement, defs, visited, refModelNamePrefix, kType)
          StructureKind.MAP ->
              mapToSchema(descriptor, jsonElement, defs, visited, refModelNamePrefix, kType)
          is PolymorphicKind.SEALED ->
              sealedToSchema(descriptor, serialName, defs, visited, refModelNamePrefix, kType)
          is PolymorphicKind.OPEN ->
              throw IllegalArgumentException(
                  "PolymorphicKind.OPEN is not supported for JSON Schema generation"
              )
          else -> throw IllegalArgumentException("Unsupported descriptor kind: ${descriptor.kind}")
        }

    return if (descriptor.isNullable) {
      wrapNullable(baseSchema)
    } else {
      baseSchema
    }
  }

  private fun primitiveToSchema(
      kind: PrimitiveKind,
      serialName: String,
      jsonElement: JsonElement?,
  ): NODE {
    val (type, format) =
        when (kind) {
          PrimitiveKind.STRING -> "string" to null
          PrimitiveKind.INT -> "integer" to "int32"
          PrimitiveKind.LONG -> "integer" to "int64"
          PrimitiveKind.DOUBLE -> "number" to "double"
          PrimitiveKind.FLOAT -> "number" to "float"
          PrimitiveKind.BOOLEAN -> "boolean" to null
          PrimitiveKind.BYTE -> "integer" to "int32"
          PrimitiveKind.SHORT -> "integer" to "int32"
          PrimitiveKind.CHAR -> "string" to null
        }
    val resolvedFormat = format ?: formatMappings[serialName.substringAfterLast('.')]

    val fields = mutableListOf<Pair<String, NODE>>()
    fields.add("type" to json.string(type))
    resolvedFormat?.let { fields.add("format" to json.string(it)) }
    if (jsonElement != null && jsonElement !is JsonNull) {
      fields.add("example" to convertJsonElement(jsonElement))
    }

    return json.obj(fields)
  }

  private fun enumToSchema(
      descriptor: SerialDescriptor,
      serialName: String,
      defs: DefinitionAccumulator<NODE>,
      refModelNamePrefix: String?,
  ): NODE {
    val shortName = serialName.substringAfterLast('.')
    val defName =
        addDefinition(
            defs = defs,
            serialName = serialName,
            shortName = shortName,
            schema = {
              val elementNames =
                  (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
              json.obj(
                  "type" to json.string("string"),
                  "enum" to json.array(elementNames.map { json.string(it) }),
              )
            },
            refModelNamePrefix = refModelNamePrefix,
        )

    val refPath = buildRefPath(defName)
    return json.obj("\$ref" to json.string(refPath))
  }

  private fun classToSchema(
      descriptor: SerialDescriptor,
      serialName: String,
      jsonElement: JsonElement?,
      defs: DefinitionAccumulator<NODE>,
      visited: MutableSet<String>,
      refModelNamePrefix: String?,
      kType: KType? = null,
  ): NODE {

    if (visited.contains(serialName)) {
      val shortName = serialName.substringAfterLast('.')
      val refName = resolveDefinitionName(defs, serialName, shortName, refModelNamePrefix)
      val refPath = buildRefPath(refName)
      return json.obj("\$ref" to json.string(refPath))
    }

    visited.add(serialName)

    val jsonObj = jsonElement as? JsonObject
    val (properties, requiredFields) =
        buildObjectProperties(descriptor, jsonObj, defs, visited, refModelNamePrefix, kType)

    val schemaFields = mutableListOf<Pair<String, NODE>>()
    schemaFields.add("type" to json.string("object"))
    schemaFields.add("properties" to json.obj(properties))
    if (requiredFields.isNotEmpty()) {
      schemaFields.add("required" to json.array(requiredFields.map { json.string(it) }))
    }

    val objectSchema = json.obj(schemaFields)

    val shortName = serialName.substringAfterLast('.')
    val defName =
        addDefinition(
            defs = defs,
            serialName = serialName,
            shortName = shortName,
            schema = { objectSchema },
            refModelNamePrefix = refModelNamePrefix,
        )

    val refPath = buildRefPath(defName)
    return json.obj("\$ref" to json.string(refPath))
  }

  private fun listToSchema(
      descriptor: SerialDescriptor,
      jsonElement: JsonElement?,
      defs: DefinitionAccumulator<NODE>,
      visited: MutableSet<String>,
      refModelNamePrefix: String?,
      kType: KType? = null,
  ): NODE {
    val elementDescriptor = descriptor.getElementDescriptor(0)
    val elementKType = kType?.arguments?.firstOrNull()?.type

    val itemJsonElement = (jsonElement as? JsonArray)?.firstOrNull()
    val itemSchema =
        descriptorToSchema(
            descriptor = elementDescriptor,
            jsonElement = itemJsonElement,
            defs = defs,
            visited = visited,
            refModelNamePrefix = refModelNamePrefix,
            kType = elementKType,
        )

    return json.obj("type" to json.string("array"), "items" to itemSchema)
  }

  private fun mapToSchema(
      descriptor: SerialDescriptor,
      jsonElement: JsonElement?,
      defs: DefinitionAccumulator<NODE>,
      visited: MutableSet<String>,
      refModelNamePrefix: String?,
      kType: KType? = null,
  ): NODE {
    val keyDescriptor = descriptor.getElementDescriptor(0)
    if (keyDescriptor.kind !is PrimitiveKind.STRING) {
      throw IllegalArgumentException(
          "Map keys must be strings for JSON Schema generation. Found: ${keyDescriptor.kind}"
      )
    }

    val valueDescriptor = descriptor.getElementDescriptor(1)
    val valueKType = kType?.arguments?.getOrNull(1)?.type

    val valueJsonElement = (jsonElement as? JsonObject)?.values?.firstOrNull()
    val valueSchema =
        descriptorToSchema(
            descriptor = valueDescriptor,
            jsonElement = valueJsonElement,
            defs = defs,
            visited = visited,
            refModelNamePrefix = refModelNamePrefix,
            kType = valueKType,
        )

    return json.obj("type" to json.string("object"), "additionalProperties" to valueSchema)
  }

  private fun wrapNullable(schema: NODE): NODE {
    return when (nullableStrategy) {
      NullableStrategy.ANYOF ->
          json.obj("anyOf" to json.array(listOf(schema, json.obj("type" to json.string("null")))))

      NullableStrategy.TYPE_ARRAY -> {
        val fields = json.fields(schema).toList()
        val hasRef = fields.any { (k, _) -> k == "\$ref" }

        if (hasRef) {
          // $ref types: return as-is (field already excluded from required)
          schema
        } else {
          // Primitive/inline types: merge "null" into type array
          val typeField = fields.find { (k, _) -> k == "type" }
          if (typeField != null) {
            val newFields =
                fields.map { (k, v) ->
                  if (k == "type") k to json.array(listOf(v, json.string("null"))) else k to v
                }
            json.obj(newFields)
          } else {
            // Fallback for schemas without type or $ref (shouldn't occur in practice)
            json.obj("anyOf" to json.array(listOf(schema, json.obj("type" to json.string("null")))))
          }
        }
      }
    }
  }

  private fun convertJsonElement(element: JsonElement): NODE {
    return when (element) {
      is JsonPrimitive -> {
        when {
          element.isString -> json.string(element.content)
          element.content == "true" || element.content == "false" ->
              json.boolean(element.content.toBoolean())
          else -> {
            val content = element.content
            content.toBigIntegerOrNull()?.let { json.number(it) }
                ?: content.toBigDecimalOrNull()?.let { json.number(it) }
                ?: json.string(content)
          }
        }
      }
      is JsonArray -> json.array(element.map { convertJsonElement(it) })
      is JsonObject -> json.obj(element.entries.map { (k, v) -> k to convertJsonElement(v) })
      is JsonNull -> json.nullNode()
    }
  }

  private fun addDefinition(
      defs: DefinitionAccumulator<NODE>,
      serialName: String,
      shortName: String,
      schema: () -> NODE,
      refModelNamePrefix: String?,
  ): String {
    val existingKey =
        defs.schemas.keys.find { key ->
          val strippedKey = refModelNamePrefix?.let { key.removePrefix(it) } ?: key
          strippedKey == shortName || strippedKey == serialName.replace('.', '_')
        }

    if (existingKey != null) {
      val existingSerialName = defs.serialNames[existingKey]
      if (existingSerialName != serialName) {
        val oldFullName = existingSerialName?.replace('.', '_') ?: existingKey
        val oldPrefixedName = refModelNamePrefix?.let { "$it$oldFullName" } ?: oldFullName
        val existing =
            defs.schemas.remove(existingKey)
                ?: throw IllegalStateException(
                    "Expected definition '$existingKey' not found during collision resolution"
                )
        defs.schemas[oldPrefixedName] = existing
        defs.serialNames.remove(existingKey)
        defs.serialNames[oldPrefixedName] = existingSerialName ?: existingKey

        val newFullName = serialName.replace('.', '_')
        val newPrefixedName = refModelNamePrefix?.let { "$it$newFullName" } ?: newFullName
        defs.schemas[newPrefixedName] = schema()
        defs.serialNames[newPrefixedName] = serialName
        return newPrefixedName
      } else {
        return existingKey
      }
    } else {
      val defName = refModelNamePrefix?.let { "$it$shortName" } ?: shortName
      defs.schemas[defName] = schema()
      defs.serialNames[defName] = serialName
      return defName
    }
  }

  private fun resolveDefinitionName(
      defs: DefinitionAccumulator<NODE>,
      serialName: String,
      shortName: String,
      refModelNamePrefix: String?,
  ): String {
    return defs.schemas.keys.find { key ->
      val strippedKey = refModelNamePrefix?.let { key.removePrefix(it) } ?: key
      strippedKey == shortName || strippedKey == serialName.replace('.', '_')
    } ?: (refModelNamePrefix?.let { "$it$shortName" } ?: shortName)
  }

  private fun buildRefPath(defName: String): String {
    return "#/$refLocationPrefix/$defName"
  }

  private fun buildObjectProperties(
      descriptor: SerialDescriptor,
      jsonObj: JsonObject?,
      defs: DefinitionAccumulator<NODE>,
      visited: MutableSet<String>,
      refModelNamePrefix: String?,
      kType: KType? = null,
  ): Pair<List<Pair<String, NODE>>, List<String>> {
    val properties = mutableListOf<Pair<String, NODE>>()
    val requiredFields = mutableListOf<String>()
    val ownerKClass = kType?.classifier as? KClass<*>

    for (i in 0 until descriptor.elementsCount) {
      val elementName = descriptor.getElementName(i)
      val elementDescriptor = descriptor.getElementDescriptor(i)
      val elementJsonValue = jsonObj?.get(elementName)
      val elementKType = resolvePropertyType(ownerKClass, elementName)

      val elementSchema =
          descriptorToSchema(
              descriptor = elementDescriptor,
              jsonElement = elementJsonValue,
              defs = defs,
              visited = visited,
              refModelNamePrefix = refModelNamePrefix,
              kType = elementKType,
          )

      properties.add(elementName to elementSchema)

      val isOptional = descriptor.isElementOptional(i)
      val isNullable = elementDescriptor.isNullable
      if (!isOptional && !isNullable) {
        requiredFields.add(elementName)
      }
    }

    return properties to requiredFields
  }

  private fun sealedToSchema(
      descriptor: SerialDescriptor,
      serialName: String,
      defs: DefinitionAccumulator<NODE>,
      visited: MutableSet<String>,
      refModelNamePrefix: String?,
      kType: KType? = null,
  ): NODE {
    require(descriptor.elementsCount >= 2) {
      "Unexpected SEALED descriptor structure: elementsCount=${descriptor.elementsCount}"
    }

    val classDiscriminator = kotlinxJson.configuration.classDiscriminator

    val discriminatorName = descriptor.getElementName(0)
    require(discriminatorName == classDiscriminator) {
      "Unexpected SEALED descriptor structure: element[0].name=${discriminatorName}, expected=$classDiscriminator"
    }

    val subclassContainerDescriptor = descriptor.getElementDescriptor(1)

    val sealedKClass =
        try {
          Class.forName(serialName).kotlin
        } catch (_: ClassNotFoundException) {
          // @SerialName on the sealed parent makes serialName differ from the FQ class name.
          // Fall back to the KType threaded through the traversal.
          (kType?.classifier as? KClass<*>)
              ?: throw IllegalStateException(
                  "Cannot load sealed class '${serialName}' for example discovery. " +
                      "Ensure the class is on the classpath or has a resolvable owner type.",
              )
        }
    val examples = sealedClassExampleProvider.getExamples(sealedKClass)
    val examplesBySerialName =
        examples.associateBy { example ->
          kotlinxJson.serializersModule.serializer(example::class.java).descriptor.serialName
        }

    // Map @SerialName values to Kotlin class names and KClasses to avoid definition key
    // collisions when multiple sealed hierarchies share the same @SerialName discriminator values,
    // and to thread KType context to subclass property traversal.
    val leafSubclasses = collectLeafSubclasses(sealedKClass)
    val subclassClassNames: Map<String, String> =
        leafSubclasses.associate { subclass ->
          val serializer = kotlinxJson.serializersModule.serializer(subclass.java)
          serializer.descriptor.serialName to subclass.simpleName!!
        }
    val subclassKClasses: Map<String, KClass<*>> =
        leafSubclasses.associate { subclass ->
          val serializer = kotlinxJson.serializersModule.serializer(subclass.java)
          serializer.descriptor.serialName to subclass
        }

    val oneOfRefs = mutableListOf<NODE>()
    val discriminatorMapping = mutableMapOf<String, String>()

    for (i in 0 until subclassContainerDescriptor.elementsCount) {
      val subclassDescriptor = subclassContainerDescriptor.getElementDescriptor(i)
      val discriminatorValue = subclassDescriptor.serialName
      val shortName =
          subclassClassNames[discriminatorValue] ?: discriminatorValue.substringAfterLast('.')

      val example = examplesBySerialName[discriminatorValue]
      val exampleJson =
          example?.let {
            kotlinxJson.encodeToJsonElement(
                kotlinxJson.serializersModule.serializer(it::class.java) as KSerializer<Any>,
                it,
            ) as? JsonObject
          }

      val properties = mutableListOf<Pair<String, NODE>>()
      val requiredFields = mutableListOf<String>()

      properties.add(
          classDiscriminator to
              json.obj(
                  "type" to json.string("string"),
                  "enum" to json.array(listOf(json.string(discriminatorValue))),
              )
      )
      requiredFields.add(classDiscriminator)

      val subclassKType = subclassKClasses[discriminatorValue]?.starProjectedType
      val (subclassProperties, subclassRequiredFields) =
          buildObjectProperties(
              subclassDescriptor,
              exampleJson,
              defs,
              visited,
              refModelNamePrefix,
              subclassKType,
          )
      properties.addAll(subclassProperties)
      requiredFields.addAll(subclassRequiredFields)

      val schemaFields = mutableListOf<Pair<String, NODE>>()
      schemaFields.add("type" to json.string("object"))
      schemaFields.add("properties" to json.obj(properties))
      if (requiredFields.isNotEmpty()) {
        schemaFields.add("required" to json.array(requiredFields.map { json.string(it) }))
      }

      val subclassSchema = json.obj(schemaFields)

      val subclassDefName =
          addDefinition(
              defs = defs,
              serialName = discriminatorValue,
              shortName = shortName,
              schema = { subclassSchema },
              refModelNamePrefix = refModelNamePrefix,
          )

      val subclassRefPath = buildRefPath(subclassDefName)
      oneOfRefs.add(json.obj("\$ref" to json.string(subclassRefPath)))
      discriminatorMapping[discriminatorValue] = subclassRefPath
    }

    val parentQualifiedName = sealedKClass.qualifiedName ?: serialName
    val parentShortName = sealedKClass.simpleName ?: serialName.substringAfterLast('.')
    val parentSchema =
        json.obj(
            "oneOf" to json.array(oneOfRefs),
            "discriminator" to
                json.obj(
                    "propertyName" to json.string(classDiscriminator),
                    "mapping" to
                        json.obj(discriminatorMapping.map { (k, v) -> k to json.string(v) }),
                ),
        )

    val parentDefName =
        addDefinition(
            defs = defs,
            serialName = parentQualifiedName,
            shortName = parentShortName,
            schema = { parentSchema },
            refModelNamePrefix = refModelNamePrefix,
        )

    val refPath = buildRefPath(parentDefName)
    return json.obj("\$ref" to json.string(refPath))
  }

  /** Recursively collects all concrete (non-sealed) subclasses from a sealed hierarchy. */
  private fun collectLeafSubclasses(klass: KClass<*>): List<KClass<*>> =
      klass.sealedSubclasses.flatMap { sub ->
        if (sub.isSealed) collectLeafSubclasses(sub) else listOf(sub)
      }

  /**
   * Resolves a property's [KType] by matching the serialized element name to a Kotlin property.
   * Checks both the Kotlin property name and any `@SerialName` annotation on the property, since
   * the serialized name may differ from the Kotlin name.
   */
  private fun resolvePropertyType(ownerKClass: KClass<*>?, elementName: String): KType? {
    if (ownerKClass == null) return null
    return ownerKClass.memberProperties
        .find { prop ->
          prop.name == elementName ||
              prop.annotations.filterIsInstance<kotlinx.serialization.SerialName>().any {
                it.value == elementName
              }
        }
        ?.returnType
  }

  /** Resolves the inner type of an inline value class. */
  private fun resolveInlineInnerType(kType: KType?): KType? {
    val kClass = kType?.classifier as? KClass<*> ?: return null
    return kClass.memberProperties.firstOrNull()?.returnType
  }
}
