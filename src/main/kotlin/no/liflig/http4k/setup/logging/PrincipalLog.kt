package no.liflig.http4k.setup.logging

import no.liflig.http4k.setup.context.RequestContext
import org.http4k.core.Request

/**
 * Represents a "principal" (user, or requesting entity) that will be logged.
 *
 * This special logging view of a principal should exclude identifiable information like names,
 * email, etc.
 */
interface PrincipalLog

/**
 * Attaches the given [PrincipalLog] to the request for logging. This can later be retrieved by
 * [getPrincipalLog]. You can use this in the `principalLog` argument of
 * [LifligBasicApiSetup.create][no.liflig.http4k.setup.LifligBasicApiSetup.create] to get the user
 * principal for logging, though you likely have to cast to your own concrete principal type
 * (consider using `as?` for graceful casting).
 *
 * ### Example
 *
 * First, call `attachPrincipalLog` after authenticating a request:
 * ```
 * class JwtAuthFilter : Filter {
 *   // ...
 *
 *   fun validateToken(request: Request) {
 *     val tokenClaims = getTokenClaims(request)
 *
 *     // Once we have validated the token and know the user's identity, we
 *     // can call this to attach info about the requesting user for logging
 *     request.attachPrincipalLog(
 *         // You may use a different concrete PrincipalLog type here
 *         LifligUserPrincipalLog(
 *             id = tokenClaims.userId,
 *             name = tokenClaims.cognitoUsername,
 *             roles = tokenClaims.roles,
 *         ),
 *     )
 *   }
 * }
 * ```
 *
 * Then, call `getPrincipalLog` in your API setup:
 * ```
 * LifligBasicApiSetup(
 *     // ...
 * ).create(
 *     // This lambda is called by our LoggingFilter to extract the principal for
 *     // logging. Logging is done after the request completes, so if we called
 *     // attachPrincipalLog in an auth filter, it will be available here.
 *     principalLog = { request ->
 *       // Cast to your concrete PrincipalLog type here
 *       request.getPrincipalLog() as? LifligUserPrincipalLog
 *     },
 * )
 * ```
 */
fun Request.attachPrincipalLog(principalLog: PrincipalLog) {
  RequestContext.setPrincipalLog(this, principalLog)
}

/**
 * Returns the [PrincipalLog] that was previously attached to the request with [attachPrincipalLog],
 * if any. You can use this in the `principalLog` argument of
 * [LifligBasicApiSetup.create][no.liflig.http4k.setup.LifligBasicApiSetup.create] to get the user
 * principal for logging, though you likely have to cast to your own concrete principal type
 * (consider using `as?` for graceful casting).
 *
 * ### Example
 *
 * First, call `attachPrincipalLog` after authenticating a request:
 * ```
 * class JwtAuthFilter : Filter {
 *   // ...
 *
 *   fun validateToken(request: Request) {
 *     val tokenClaims = getTokenClaims(request)
 *
 *     // Once we have validated the token and know the user's identity, we
 *     // can call this to attach info about the requesting user for logging
 *     request.attachPrincipalLog(
 *         // You may use a different concrete PrincipalLog type here
 *         LifligUserPrincipalLog(
 *             id = tokenClaims.userId,
 *             name = tokenClaims.cognitoUsername,
 *             roles = tokenClaims.roles,
 *         ),
 *     )
 *   }
 * }
 * ```
 *
 * Then, call `getPrincipalLog` in your API setup:
 * ```
 * LifligBasicApiSetup(
 *     // ...
 * ).create(
 *     // This lambda is called by our LoggingFilter to extract the principal for
 *     // logging. Logging is done after the request completes, so if we called
 *     // attachPrincipalLog in an auth filter, it will be available here.
 *     principalLog = { request ->
 *       // Cast to your concrete PrincipalLog type here
 *       request.getPrincipalLog() as? LifligUserPrincipalLog
 *     },
 * )
 * ```
 */
fun Request.getPrincipalLog(): PrincipalLog? {
  return RequestContext.getPrincipalLog(this)
}
