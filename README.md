# liflig-http4k-setup

Default setup for Liflig projects using http4k.

Encapsulates the basic API setup for Liflig services that removes complexity that is a common cause of errors.
It also facilitates handling requests similarly by providing default core filters that gives the
following functionality:

- Log in json-format containing metadata about request. E.g. log id, request chain id, user info,
  headers, exception stacktrace etc.
- Sets up default filters in a specific order so that log is enriched properly with data.
- Catching unhandled exceptions and respond in standard json-format.
- OpenTelemetry setup for recording exceptions.
- Sets Cors policy for API.
- Standard way of handling validation errors by lens failure in contract APIs (E.g. invalid request param) and
  respond in standard json-format.
- Convenience functions for explicit handling of application errors that helps to return error response in standard error format and
  logs throwable in API request log.

We use the following standard for default error response json body: https://datatracker.ietf.org/doc/html/rfc7807

## Build & Test

```sh
mvn clean verify
```

## Lint code

To only check linting (no tests etc):

```bash
mvn spotless:check
```

To format (does not fail on lint errors):

```bash
mvn spotless:apply
```

## Usage example
Api server setup:
```kotlin
// ApiServerSetup.kt
class ApiServerSetup(config: Config) {
  private val basicApiSetup =
    LifligBasicApiSetup(
      logHandler =
      LoggingFilter.createLogHandler(
        printStacktraceToConsole = config.printStackTraceToConsole,
        principalLogSerializer =
        TODO(
          "Add custom principal log serializer or remove param to use liflig default"),
        suppressSuccessfulHealthChecks = true),
      logHttpBody = config.logHttpBody,
      corsPolicy = TODO("Cors policy or remove param if no need"),
      contentTypesToLog = TODO("Add content types or remove param if only json is needed"))

  private val healthService = HealthService(config.applicationName, config.buildInfo)
  private val api = createApi(basicApiSetup, healthService)
  private val port = config.serverPort

  fun init(): Http4kServer = api.asJettyServer(port)
}

private fun RoutingHttpHandler.asJettyServer(developmentPort: Int): Http4kServer {
  val port = getPortToListenOn(developmentPort)
  return asServer(Jetty(port, httpNoServerVersionHeader(port)))
}

private fun getPortToListenOn(developmentPort: Int): Int {
  val servicePortFromEnv = System.getenv("SERVICE_PORT")
  return servicePortFromEnv?.toInt() ?: developmentPort
}

// Avoid leaking Jetty version in http response header "Server".
private fun httpNoServerVersionHeader(port: Int): ConnectorBuilder = { server ->
  http(port)(server).apply {
    connectionFactories.filterIsInstance<HttpConnectionFactory>().forEach {
      it.httpConfiguration.sendServerVersion = false
      it.httpConfiguration.requestHeaderSize = 16384
    }
  }
}
```

API setup:
```kotlin
// CreateApi.kt
internal fun createApi(
    basicApiSetup: LifligBasicApiSetup,
    healthService: HealthService,
): RoutingHttpHandler {
  val (coreFilters, errorResponseRenderer) = basicApiSetup.config(principalLog = { null })

  return coreFilters.then(
      routes(
          "/api" bind contractApi(errorResponseRenderer),
          "/health" bind Method.GET to healthService.endpoint(),
          swaggerUiLite { url = "/api/docs/openapi-schema.json" }))
}

private fun contractApi(
    errorResponseRenderer: ContractLensErrorResponseRenderer,
): ContractRoutingHttpHandler = contract {
  renderer = openApi3Renderer(errorResponseRenderer)
  descriptionPath = "/docs/openapi-schema.json"
  routes += TODO("MyFunkyFreshApi().routes")
}

private fun openApi3Renderer(errorResponseRenderer: ContractLensErrorResponseRenderer) =
    OpenApi3(
        apiInfo = ApiInfo("TODO: API title", "v1.0"),
        servers = listOf(ApiServer(url = Uri.of("/"))),
        json = Jackson,
        apiRenderer = ApiRenderer.Auto(json = OpenAPIJackson),
        errorResponseRenderer = errorResponseRenderer,
    )
```
