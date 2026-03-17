package no.liflig.http4k.kotlinx.openapi

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json as KotlinxJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.liflig.http4k.kotlinx.jsonschema.KotlinxSerializationJsonSchemaCreator
import org.http4k.contract.contract
import org.http4k.contract.meta
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.ApiServer
import org.http4k.core.Body
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Uri
import org.http4k.format.KotlinxSerialization
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.lens.Query
import org.http4k.lens.enum
import org.junit.jupiter.api.Test

class KotlinxOpenApi3RendererTest {

  @Serializable
  data class CreateRequest(
      val name: String,
      val value: Int,
  )

  @Serializable
  data class CreateResponse(
      val id: String,
      val created: Boolean,
  )

  @Serializable
  data class NullableFieldDto(
      val required: String,
      val optional: String? = null,
      val optionalEvent: EventPayload? = null,
  )

  @Serializable
  sealed class EventPayload {
    @Serializable @SerialName("created") data class Created(val name: String) : EventPayload()

    @Serializable @SerialName("deleted") data object Deleted : EventPayload()

    companion object {
      val example: EventPayload = Created("test-item")
    }
  }

  @Serializable
  data class EventResponse(
      val event: EventPayload,
  )

  enum class StatusFilter {
    ACTIVE,
    INACTIVE,
    ALL,
  }

  private val json = KotlinxSerialization

  private val kotlinxJson = KotlinxJson { ignoreUnknownKeys = true }

  private val schema =
      KotlinxSerializationJsonSchemaCreator<JsonElement>(
          json = json,
          kotlinxJson = kotlinxJson,
      )

  private fun buildContract(block: org.http4k.contract.ContractBuilder.() -> Unit) = contract {
    this.renderer =
        openApi3WithKotlinx(
            apiInfo = ApiInfo("Test API", "1.0.0"),
            json = this@KotlinxOpenApi3RendererTest.json,
            schema = this@KotlinxOpenApi3RendererTest.schema,
            servers = listOf(ApiServer(Uri.of("http://localhost:8080"))),
        )
    block()
  }

  private fun fetchSpec(app: org.http4k.core.HttpHandler): kotlinx.serialization.json.JsonObject {
    val response = app(Request(GET, "/"))
    response.status.code shouldBe 200
    return KotlinxJson.parseToJsonElement(response.bodyString()).jsonObject
  }

  @Test
  fun `renders openapi document without jackson`() {
    val requestLens = Body.auto<CreateRequest>().toLens()
    val responseLens = Body.auto<CreateResponse>().toLens()

    val app = buildContract {
      routes +=
          "/items" meta
              {
                summary = "Create item"
                receiving(requestLens to CreateRequest("test", 42))
                returning(OK, responseLens to CreateResponse("id-1", true))
              } bindContract
              POST to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)

    spec["openapi"]?.jsonPrimitive?.content shouldBe "3.1.0"

    val info = spec["info"]?.jsonObject.shouldNotBeNull()
    info["title"]?.jsonPrimitive?.content shouldBe "Test API"

    val paths = spec["paths"]?.jsonObject.shouldNotBeNull()
    paths shouldContainKey "/items"

    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()
    schemas.shouldNotBeEmpty()
  }

  @Test
  fun `renders sealed class in response body`() {
    val responseLens = Body.auto<EventResponse>().toLens()

    val app = buildContract {
      routes +=
          "/events" meta
              {
                summary = "Get events"
                returning(OK, responseLens to EventResponse(EventPayload.example))
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)
    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()

    schemas shouldContainKey "EventPayload"
    val eventPayload = schemas["EventPayload"]?.jsonObject.shouldNotBeNull()
    eventPayload shouldContainKey "oneOf"
    schemas shouldContainKey "Created"
  }

  @Test
  fun `renders enum query parameter`() {
    val statusLens = Query.enum<StatusFilter>().required("status")

    val app = buildContract {
      routes +=
          "/items" meta
              {
                summary = "List items"
                queries += statusLens
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)
    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()

    val enumDef =
        schemas.values.firstOrNull { def ->
          val obj = def.jsonObject
          obj["type"]?.jsonPrimitive?.content == "string" && obj.containsKey("enum")
        }
    enumDef.shouldNotBeNull()

    val enumValues =
        enumDef.jsonObject["enum"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    enumValues shouldContain "ACTIVE"
    enumValues shouldContain "INACTIVE"
    enumValues shouldContain "ALL"
  }

  @Test
  fun `renders raw json object body via JsonToJsonSchema`() {
    val bodyLens = json.body("raw json").toLens()
    val example = json.obj("name" to json.string("test"), "count" to json.number(42))

    val app = buildContract {
      routes +=
          "/raw" meta
              {
                summary = "Raw JSON body"
                receiving(bodyLens to example)
              } bindContract
              POST to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)
    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()

    // Raw JSON object should produce a schema with properties derived from the JSON structure
    val rawDef =
        schemas.values.firstOrNull { def ->
          val obj = def.jsonObject
          obj["type"]?.jsonPrimitive?.content == "object" && obj.containsKey("properties")
        }
    rawDef.shouldNotBeNull()

    val properties = rawDef.jsonObject["properties"]?.jsonObject.shouldNotBeNull()
    properties shouldContainKey "name"
    properties shouldContainKey "count"
  }

  @Test
  fun `nullable inline property uses type array in 3_1 document`() {
    val responseLens = Body.auto<NullableFieldDto>().toLens()

    val app = buildContract {
      routes +=
          "/nullable" meta
              {
                summary = "Nullable fields"
                returning(OK, responseLens to NullableFieldDto("hello"))
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)
    spec["openapi"]?.jsonPrimitive?.content shouldBe "3.1.0"

    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()

    val dto = schemas["NullableFieldDto"]?.jsonObject.shouldNotBeNull()

    val properties = dto["properties"]?.jsonObject.shouldNotBeNull()

    // The "optional" field should use type array with "null" (TYPE_ARRAY strategy default)
    val optional = properties["optional"]?.jsonObject.shouldNotBeNull()
    val typeArray = optional["type"]?.jsonArray.shouldNotBeNull()
    val types = typeArray.map { it.jsonPrimitive.content }
    types shouldContain "null"
    types shouldContain "string"
  }

  @Test
  fun `nullable ref property uses plain ref in 3_1 document`() {
    val responseLens = Body.auto<NullableFieldDto>().toLens()

    val app = buildContract {
      routes +=
          "/nullable-ref" meta
              {
                summary = "Nullable ref fields"
                returning(
                    OK,
                    responseLens to
                        NullableFieldDto("hello", optionalEvent = EventPayload.Created("x")),
                )
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)

    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()

    val dto = schemas["NullableFieldDto"]?.jsonObject.shouldNotBeNull()
    val properties = dto["properties"]?.jsonObject.shouldNotBeNull()

    // The "optionalEvent" field references a sealed class — TYPE_ARRAY strategy emits plain $ref
    val optionalEvent = properties["optionalEvent"]?.jsonObject.shouldNotBeNull()
    optionalEvent["\$ref"]?.jsonPrimitive?.content.shouldNotBeNull()
    optionalEvent["anyOf"] shouldBe null
  }

  @Test
  fun `rendered spec is valid openapi 3_1`() {
    val requestLens = Body.auto<CreateRequest>().toLens()
    val responseLens = Body.auto<CreateResponse>().toLens()
    val eventLens = Body.auto<EventResponse>().toLens()
    val nullableLens = Body.auto<NullableFieldDto>().toLens()
    val statusLens = Query.enum<StatusFilter>().required("status")

    val app = buildContract {
      routes +=
          "/items" meta
              {
                summary = "Create item"
                receiving(requestLens to CreateRequest("test", 42))
                returning(OK, responseLens to CreateResponse("id-1", true))
              } bindContract
              POST to
              { _ ->
                Response(OK)
              }
      routes +=
          "/events" meta
              {
                summary = "Get events"
                returning(OK, eventLens to EventResponse(EventPayload.Created("test")))
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
      routes +=
          "/nullable" meta
              {
                summary = "Nullable fields"
                returning(
                    OK,
                    nullableLens to
                        NullableFieldDto("hello", optionalEvent = EventPayload.Created("x")),
                )
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
      routes +=
          "/filtered" meta
              {
                summary = "Filtered list"
                queries += statusLens
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val response = app(Request(GET, "/"))

    // Write spec file for optional manual validation (e.g. `npx @redocly/cli lint
    // target/openapi-spec.json`)
    val specFile = java.io.File("target/openapi-spec.json")
    specFile.parentFile.mkdirs()
    specFile.writeText(response.bodyString())

    // Validate with swagger-parser
    val parseResult =
        io.swagger.parser.OpenAPIParser().readContents(response.bodyString(), null, null)
    val errors = parseResult.messages.orEmpty()
    errors.shouldBeEmpty()

    val openApi = parseResult.openAPI.shouldNotBeNull()
    openApi.openapi shouldBe "3.1.0"
  }

  @Test
  fun `renders raw json array body via JsonToJsonSchema`() {
    val bodyLens = json.body("raw json array").toLens()
    val example = json.array(listOf(json.obj("id" to json.string("1"))))

    val app = buildContract {
      routes +=
          "/raw-array" meta
              {
                summary = "Raw JSON array body"
                receiving(bodyLens to example)
              } bindContract
              POST to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)
    val paths = spec["paths"]?.jsonObject.shouldNotBeNull()
    paths shouldContainKey "/raw-array"
  }
}
