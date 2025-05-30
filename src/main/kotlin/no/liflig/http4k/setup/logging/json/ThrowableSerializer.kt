@file:UseSerializers(ThrowableSerializer::class, StackTraceElementSerializer::class)

package no.liflig.http4k.setup.logging.json

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * TODO: Suppressed field is causing the following exception when initializing
 *   Serialized.serializer(). Ignoring for now.
 *
 * java.lang.NullPointerException: Parameter specified as non-null is null: method
 * kotlinx.serialization.internal.ArrayClassDesc.<init>, parameter elementDesc
 */
internal object ThrowableSerializer : KSerializer<Throwable> {
  override val descriptor: SerialDescriptor = Serialized.serializer().descriptor

  override fun serialize(encoder: Encoder, value: Throwable): Unit =
      Serialized.serializer()
          .serialize(
              encoder,
              Serialized(
                  value.toString(),
                  value.stackTrace,
                  // value.suppressed,
                  value.cause,
              ),
          )

  override fun deserialize(decoder: Decoder): Throwable = throw NotImplementedError()

  @Suppress("unused")
  @Serializable
  private class Serialized(
      val value: String,
      val stackTrace: Array<StackTraceElement>?,
      // val suppressed: Array<Throwable>?,
      val cause: Throwable?,
  )
}

internal object StackTraceElementSerializer : KSerializer<StackTraceElement> {
  override val descriptor: SerialDescriptor = Serialized.serializer().descriptor

  override fun serialize(encoder: Encoder, value: StackTraceElement): Unit =
      Serialized.serializer()
          .serialize(
              encoder,
              Serialized(
                  value.className,
                  value.methodName,
                  value.fileName,
                  value.lineNumber,
              ),
          )

  override fun deserialize(decoder: Decoder): StackTraceElement = throw NotImplementedError()

  @Suppress("unused")
  @Serializable
  private class Serialized(
      val declaringClass: String,
      val methodName: String,
      val fileName: String?,
      val lineNumber: Int,
  )
}
