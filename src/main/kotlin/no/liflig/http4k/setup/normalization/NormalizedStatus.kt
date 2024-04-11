package no.liflig.http4k.setup.normalization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure

enum class NormalizedStatusCode {
  OK,
  INTERNAL_SERVER_ERROR,
  SERVICE_UNAVAILABLE,
  CLIENT_ERROR,
}

enum class ClientErrorCategory {
  BAD_REQUEST,
  NOT_FOUND,
  UNAUTHORIZED,
}

sealed class NormalizedStatus(val code: NormalizedStatusCode) {
  class Ok : NormalizedStatus(NormalizedStatusCode.OK) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false
      return true
    }

    override fun hashCode(): Int {
      return javaClass.hashCode()
    }
  }

  class InternalServerError : NormalizedStatus(NormalizedStatusCode.INTERNAL_SERVER_ERROR) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false
      return true
    }

    override fun hashCode(): Int {
      return javaClass.hashCode()
    }
  }

  class ServiceUnavailable : NormalizedStatus(NormalizedStatusCode.SERVICE_UNAVAILABLE) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false
      return true
    }

    override fun hashCode(): Int {
      return javaClass.hashCode()
    }
  }

  data class ClientError(
      val category: ClientErrorCategory,
  ) : NormalizedStatus(NormalizedStatusCode.CLIENT_ERROR)
}

class NormalizedStatusSerializer : KSerializer<NormalizedStatus> {
  override val descriptor: SerialDescriptor =
      buildClassSerialDescriptor("NormalizedStatus") {
        element("code", String.serializer().descriptor)
        element("category", String.serializer().descriptor, isOptional = true)
      }

  override fun serialize(encoder: Encoder, value: NormalizedStatus) {
    encoder.encodeStructure(descriptor) {
      encodeStringElement(descriptor, 0, value.code.toString())
      if (value is NormalizedStatus.ClientError) {
        encodeStringElement(descriptor, 1, value.category.toString())
      }
    }
  }

  override fun deserialize(decoder: Decoder): NormalizedStatus = throw NotImplementedError()
}
