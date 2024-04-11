# liflig-http4k-setup
Default setup for Liflig projects using http4k.

Encapsulates the basic API setup for services so that they handle requests
similarly. It contains default core filters to be used in all Tomra API-components that provide the
following functionality:

- Log in json-format containing metadata about request. E.g. log id, request chain id, user info,
  headers, exception stacktrace etc.
- Catching unhandled exceptions and respond in standard json-format.
- OpenTelemetry setup for recording exceptions.
- Sets Cors policy for API.
- Standard way of handling validation errors by lens failure (E.g. invalid request param) and
  respond in standard json-format.
- Convenience function for explicit handling of errors that helps to return error response in standard error format and
  logs throwable in API request log.

We use the following standard for error response json body: https://datatracker.ietf.org/doc/html/rfc7807

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
