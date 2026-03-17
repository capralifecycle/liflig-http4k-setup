# CLAUDE.md

## Overview

Kotlinx.serialization-based OpenAPI schema generation for http4k contract endpoints.

## Index

| File                                   | Contents (WHAT)                                                   | Read When (WHEN)                                                     |
| -------------------------------------- | ----------------------------------------------------------------- | -------------------------------------------------------------------- |
| `pom.xml`                              | Maven project config, dependencies, kotlinx-serialization plugin  | Setting up project, adding dependencies, understanding build         |
| `src/main/kotlin/.../kotlinx/jsonschema/KotlinxSerializationJsonSchemaCreator.kt` | JsonSchemaCreator implementation walking SerialDescriptor trees   | Debugging schema generation, understanding sealed class handling     |
| `src/main/kotlin/.../kotlinx/jsonschema/SealedClassExampleProvider.kt` | Interface for sealed subclass example discovery via companions    | Providing custom sealed class examples, debugging example resolution |
| `src/main/kotlin/.../kotlinx/openapi/KotlinxOpenApi3Renderer.kt` | ApiRenderer combining OpenApi3ApiRenderer + KotlinxSerializationJsonSchemaCreator | Understanding Jackson-free OpenAPI rendering, enum fallback      |
| `src/main/kotlin/.../kotlinx/openapi/OpenApi3WithKotlinx.kt` | Helper factory defaulting to OpenAPI 3.1.0                        | Wiring OpenApi3 with kotlinx.serialization                           |
| `src/test/kotlin/.../kotlinx/jsonschema/TestDtos.kt` | Test DTOs covering primitives, nullables, sealed classes, maps    | Writing tests, understanding supported types                         |
| `src/test/kotlin/.../kotlinx/jsonschema/KotlinxSerializationJsonSchemaCreatorTest.kt` | Approval tests for schema generation                              | Validating schema output, debugging test failures                    |
| `README.md`                            | Architecture decisions, integration guide, API feature docs       | Understanding design choices, tradeoffs, usage                       |
