package no.liflig.http4k.kotlinx.jsonschema

/**
 * Controls how nullable fields are represented in generated JSON Schema.
 *
 * Different OpenAPI code generators have varying levels of support for nullable representations.
 * This enum allows choosing the strategy that best fits the target consumer.
 */
enum class NullableStrategy {
  /**
   * Uses `type` arrays for nullable primitives and unwraps `$ref` for nullable reference types.
   * This is the recommended default for maximum code generator compatibility.
   *
   * Nullable primitives become `{"type": ["string", "null"]}` instead of `{"anyOf":
   * [{"type": "string"}, {"type": "null"}]}`.
   *
   * Nullable `$ref` types (classes, enums, sealed hierarchies) are emitted as plain `{"$ref":
   * "..."}` without an `anyOf` wrapper. The field is already excluded from the `required` array, so
   * generators correctly treat it as optional.
   *
   * Trade-off: For `$ref` types, strict JSON Schema validators will not know the field accepts
   * `null` (only that it's optional). This is acceptable for TypeScript code generators, which
   * treat optional and nullable identically.
   */
  TYPE_ARRAY,

  /**
   * Wraps all nullable types with `anyOf: [schema, {"type": "null"}]`. This is the most
   * semantically precise representation in OpenAPI 3.1 / JSON Schema 2020-12, explicitly encoding
   * that a field can be either its declared type or null.
   *
   * Use this when the schema is consumed by strict validators that distinguish "field absent" from
   * "field present with null value", or when spec correctness is more important than code generator
   * compatibility.
   *
   * Known issue: `openapi-generator-cli` (typescript-fetch) generates empty wrapper interfaces for
   * `anyOf` nullable fields instead of proper nullable types.
   */
  ANYOF,
}
