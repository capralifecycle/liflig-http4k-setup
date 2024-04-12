package no.liflig.http4k.setup

import kotlinx.serialization.Serializable
import no.liflig.http4k.setup.logging.PrincipalLog

/**
 * Defines what user info should be in API request log.
 *
 * TODO: Should this model be Liflig default? ..or maybe remove from lib?
 */
@Serializable
data class LifligUserPrincipalLog(val id: String, val name: String, val roles: List<RoleClaim>) :
    PrincipalLog

@Serializable
data class RoleClaim(
    val applicationName: String?,
    val orgId: String?,
    val roleName: String,
    val roleValue: String?,
)
