# liflig-http4k-setup

Default setup for Liflig projects using http4k.

Encapsulates the basic API setup for Liflig services that removes complexity that is a common cause of errors.
It also facilitates handling requests similarly by providing default core filters that gives the
following functionality:

- Log in json-format containing metadata about request. E.g. log id, request chain id, user info,
  headers, exception stacktrace etc.
- Sets up default filters in a specific order so that log is enriched properly with data.
- Catching unhandled exceptions and respond in standard json-format.
- OpenTelemetry setup for recording exceptions and response status codes.
- Sets Cors policy for API if needed.
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
https://github.com/capralifecycle/liflig-rest-service-baseline/blob/master/src/main/kotlin/no/liflig/baseline/api/ApiServer.kt

API setup:
https://github.com/capralifecycle/liflig-rest-service-baseline/blob/master/src/main/kotlin/no/liflig/baseline/api/Api.kt
