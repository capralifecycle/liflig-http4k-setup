package no.liflig.http4k.setup

import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import no.liflig.http4k.setup.lenses.ListQuery
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.json
import org.junit.jupiter.api.Test

class ListQueryTest {
  @Test
  fun `ListQuery accepts query params on both param=value1,value2 and param=value1&param=value2 formats`() {
    val queryParam = ListQuery.required("param")

    val handler: HttpHandler = { request ->
      val values = queryParam(request)
      Response(Status.OK).json(values)
    }

    for (url in listOf("/some/url?param=value1,value2", "/some/url?param=value1&param=value2")) {
      val response = handler(Request(Method.GET, url))
      response.status shouldBe Status.OK
      val responseBody = response.json<List<String>>()
      responseBody shouldBe listOf("value1", "value2")
    }
  }

  @Serializable @JvmInline value class Id(val value: String)

  @Test
  fun `mapValues maps values correctly`() {
    val idsQuery = ListQuery.mapValues { Id(it) }.required("ids")

    val handler: HttpHandler = { request ->
      val ids = idsQuery(request)
      Response(Status.OK).json(ids)
    }

    val response = handler(Request(Method.GET, "/some/url?ids=1,2,3"))
    response.status shouldBe Status.OK
    val responseBody = response.json<List<Id>>()
    responseBody shouldBe listOf(Id("1"), Id("2"), Id("3"))
  }

  @Test
  fun `bidirectional mapValues sets values correctly`() {
    val idsQuery =
        ListQuery.mapValues(incoming = { Id(it) }, outgoing = { id -> id.value }).required("ids")

    val handler: HttpHandler = { request ->
      val ids = idsQuery(request)
      Response(Status.OK).json(ids)
    }

    val response =
        handler(
            Request(Method.GET, "/some/url").with(idsQuery.of(listOf(Id("1"), Id("2")))),
        )
    response.status shouldBe Status.OK
    val responseBody = response.json<List<Id>>()
    responseBody shouldBe listOf(Id("1"), Id("2"))
  }

  @Test
  fun `query parameter with no value is parsed as empty list`() {
    val queryParam = ListQuery.required("param")

    val handler: HttpHandler = { request ->
      val values = queryParam(request)
      Response(Status.OK).json(values)
    }

    val response = handler(Request(Method.GET, "/some/url?param="))
    response.status shouldBe Status.OK
    val responseBody = response.json<List<String>>()
    responseBody shouldBe emptyList()
  }

  private enum class TestEnum {
    VALUE_1,
    VALUE_2,
    @Suppress("unused") VALUE_3,
  }

  @Test
  fun `enum list query`() {
    val enumQuery = ListQuery.enum<TestEnum>().required("query")

    val handler: HttpHandler = { request ->
      val enumValues = enumQuery(request)
      Response(Status.OK).json(enumValues)
    }

    val response =
        handler(
            Request(Method.GET, "/some/url")
                .with(enumQuery.of(listOf(TestEnum.VALUE_1, TestEnum.VALUE_2))),
        )
    response.status shouldBe Status.OK
    val responseBody = response.json<List<TestEnum>>()
    responseBody shouldBe listOf(TestEnum.VALUE_1, TestEnum.VALUE_2)
  }
}
