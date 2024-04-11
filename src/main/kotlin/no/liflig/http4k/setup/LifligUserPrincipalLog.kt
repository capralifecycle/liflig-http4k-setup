package no.liflig.http4k.setup

import kotlinx.serialization.Serializable
import no.liflig.http4k.setup.logging.PrincipalLog

/**
 * Defines what user info should be in API request log.
 *
 * TODO: Might not be the best location. Should be in auth library, perhaps?
 */
@Serializable
data class LifligUserPrincipalLog(val id: String, val name: String, val roles: List<RoleClaim>) :
    PrincipalLog

// Based on class in auth lib
@Serializable
data class RoleClaim(
    val applicationName: String?,
    val orgId: String?,
    val roleName: String,
    val roleValue: String?,
)
