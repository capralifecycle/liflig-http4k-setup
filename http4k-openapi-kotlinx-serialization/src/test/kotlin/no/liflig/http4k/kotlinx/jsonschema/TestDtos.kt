package no.liflig.http4k.kotlinx.jsonschema

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class SimplePrimitivesDto(
    val name: String,
    val age: Int,
    val score: Long,
    val rating: Double,
    val active: Boolean,
) {
  companion object {
    val example = SimplePrimitivesDto("Alice", 30, 100_000L, 4.5, true)
  }
}

@Serializable
data class InnerDto(
    val id: String,
    val value: Int,
) {
  companion object {
    val example = InnerDto("inner-1", 42)
  }
}

@Serializable
data class NestedObjectDto(
    val name: String,
    val inner: InnerDto,
) {
  companion object {
    val example = NestedObjectDto("outer", InnerDto.example)
  }
}

@Serializable
data class ListDto(
    val tags: List<String>,
    val items: List<InnerDto>,
) {
  companion object {
    val example = ListDto(listOf("a", "b"), listOf(InnerDto.example))
  }
}

@Serializable
data class NullableFieldDto(
    val required: String,
    val optional: String?,
    val optionalInner: InnerDto?,
) {
  companion object {
    val example = NullableFieldDto("hello", "world", InnerDto.example)
  }
}

@Serializable
data class OptionalFieldDto(
    val required: String,
    val withDefault: String = "default-value",
    val withDefaultInt: Int = 42,
) {
  companion object {
    val example = OptionalFieldDto("hello")
  }
}

@Serializable
enum class TestEnum {
  @SerialName("value_a") VALUE_A,
  @SerialName("value_b") VALUE_B,
  @SerialName("value_c") VALUE_C,
}

@Serializable
data class EnumDto(
    val status: TestEnum,
) {
  companion object {
    val example = EnumDto(TestEnum.VALUE_A)
  }
}

@Serializable
data class MapDto(
    val stringMap: Map<String, String>,
    val objectMap: Map<String, InnerDto>,
) {
  companion object {
    val example =
        MapDto(
            mapOf("key1" to "val1"),
            mapOf("obj1" to InnerDto.example),
        )
  }
}

@Serializable
sealed class SealedBase {
  abstract val commonField: String
}

@Serializable
@SerialName("child_one")
data class SealedChild1(
    override val commonField: String,
    val childOneProp: Int,
) : SealedBase() {
  companion object {
    val example = SealedChild1("common-1", 10)
  }
}

@Serializable
@SerialName("child_two")
data class SealedChild2(
    override val commonField: String,
    val childTwoProp: String,
) : SealedBase() {
  companion object {
    val example = SealedChild2("common-2", "extra")
  }
}

@Serializable
data class SealedContainerDto(
    val name: String,
    val payload: SealedBase,
) {
  companion object {
    val example = SealedContainerDto("container", SealedChild1.example)
  }
}

@Serializable
data class RecursiveDto(
    val name: String,
    val children: List<RecursiveDto>,
) {
  companion object {
    val example =
        RecursiveDto(
            "root",
            listOf(RecursiveDto("child", emptyList())),
        )
  }
}

@Serializable
@SerialName("custom_named_dto")
data class CustomSerialNameDto(
    val field: String,
) {
  companion object {
    val example = CustomSerialNameDto("test")
  }
}

// --- Value classes ---

@JvmInline @Serializable value class Kg(val value: Int)

@JvmInline @Serializable value class TrainId(val value: String)

@Serializable
data class ValueClassDto(
    val weight: Kg,
    val trainId: TrainId,
) {
  companion object {
    val example = ValueClassDto(Kg(1500), TrainId("TR-001"))
  }
}

// --- Sealed class with data object ---

@Serializable
sealed class StatusWithDataObject {
  @Serializable @SerialName("PENDING") data object Pending : StatusWithDataObject()

  @Serializable
  @SerialName("HANDLED")
  data class Handled(val mrn: String) : StatusWithDataObject() {
    companion object {
      val example = Handled("MRN-123")
    }
  }
}

@Serializable
data class StatusContainerDto(
    val status: StatusWithDataObject,
) {
  companion object {
    val example = StatusContainerDto(StatusWithDataObject.Handled.example)
  }
}

// --- Sealed interface ---

@Serializable sealed interface TransportMode

@Serializable
@SerialName("rail")
data class Rail(val trainNumber: String) : TransportMode {
  companion object {
    val example = Rail("T-42")
  }
}

@Serializable
@SerialName("road")
data class Road(val licensePlate: String) : TransportMode {
  companion object {
    val example = Road("AB12345")
  }
}

@Serializable
data class SealedInterfaceContainerDto(
    val mode: TransportMode,
) {
  companion object {
    val example = SealedInterfaceContainerDto(Rail.example)
  }
}

// --- @SerialName on properties ---

@Serializable
data class PropertySerialNameDto(
    @SerialName("train_id") val trainIdentifier: String,
    @SerialName("wagon_count") val numberOfWagons: Int,
) {
  companion object {
    val example = PropertySerialNameDto("T-42", 12)
  }
}

// --- Custom serializer with PrimitiveSerialDescriptor ---

object FakeDateSerializer : KSerializer<String> {
  override val descriptor: SerialDescriptor =
      PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

  override fun deserialize(decoder: Decoder): String = decoder.decodeString()
}

@Serializable
data class CustomSerializerDto(
    @Serializable(with = FakeDateSerializer::class) val departureDate: String,
    val name: String,
) {
  companion object {
    val example = CustomSerializerDto("2024-01-15", "Express")
  }
}

// --- Nullable + default (both optional AND nullable) ---

@Serializable
data class NullableWithDefaultDto(
    val required: String,
    val optionalNullable: String? = null,
    val optionalNullableInner: InnerDto? = null,
    val optionalWithNonNullDefault: String = "default",
) {
  companion object {
    val example = NullableWithDefaultDto("hello")
  }
}

// --- Nested maps ---

@Serializable
data class NestedMapDto(
    val permissions: Map<String, Map<String, Boolean>>,
) {
  companion object {
    val example = NestedMapDto(mapOf("admin" to mapOf("read" to true, "write" to true)))
  }
}

// --- List with default empty ---

@Serializable
data class ListWithDefaultDto(
    val required: String,
    val tags: List<String> = emptyList(),
    val items: List<InnerDto> = emptyList(),
) {
  companion object {
    val example = ListWithDefaultDto("hello", listOf("a"), listOf(InnerDto.example))
  }
}

// --- @Transient fields ---

@Serializable
data class TransientFieldDto(
    val visible: String,
    @Transient val hidden: String = "should-not-appear",
    val alsoVisible: Int,
) {
  companion object {
    val example = TransientFieldDto("hello", alsoVisible = 42)
  }
}

// --- Set type ---

@Serializable
data class SetDto(
    val uniqueTags: Set<String>,
    val uniqueItems: Set<InnerDto>,
) {
  companion object {
    val example = SetDto(setOf("a", "b"), setOf(InnerDto.example))
  }
}

// --- Deeply nested structures ---

@Serializable
data class Level4Dto(val value: String) {
  companion object {
    val example = Level4Dto("deep")
  }
}

@Serializable
data class Level3Dto(val level4: Level4Dto) {
  companion object {
    val example = Level3Dto(Level4Dto.example)
  }
}

@Serializable
data class Level2Dto(val level3: Level3Dto) {
  companion object {
    val example = Level2Dto(Level3Dto.example)
  }
}

@Serializable
data class Level1Dto(val level2: Level2Dto) {
  companion object {
    val example = Level1Dto(Level2Dto.example)
  }
}

@Serializable
data class DeeplyNestedDto(val level1: Level1Dto) {
  companion object {
    val example = DeeplyNestedDto(Level1Dto.example)
  }
}

// --- Nested sealed hierarchies (sealed containing sealed field) ---

@Serializable
sealed class InnerSealed {
  @Serializable
  @SerialName("option_a")
  data class OptionA(val a: String) : InnerSealed() {
    companion object {
      val example = OptionA("a-val")
    }
  }

  @Serializable
  @SerialName("option_b")
  data class OptionB(val b: Int) : InnerSealed() {
    companion object {
      val example = OptionB(42)
    }
  }
}

@Serializable
sealed class OuterSealed {
  @Serializable
  @SerialName("with_inner")
  data class WithInner(val inner: InnerSealed) : OuterSealed() {
    companion object {
      val example = WithInner(InnerSealed.OptionA.example)
    }
  }

  @Serializable
  @SerialName("simple_outer")
  data class SimpleOuter(val text: String) : OuterSealed() {
    companion object {
      val example = SimpleOuter("hello")
    }
  }
}

@Serializable
data class NestedSealedContainerDto(
    val outer: OuterSealed,
) {
  companion object {
    val example = NestedSealedContainerDto(OuterSealed.WithInner.example)
  }
}

// --- Sealed classes with colliding @SerialName values ---

@Serializable
sealed class ImportStatus {
  @Serializable @SerialName("PENDING") data object ImportPending : ImportStatus()

  @Serializable
  @SerialName("HANDLED")
  data class ImportHandled(val mrn: String) : ImportStatus() {
    companion object {
      val example = ImportHandled("MRN-001")
    }
  }
}

@Serializable
sealed class TransitStatus {
  @Serializable @SerialName("PENDING") data object TransitPending : TransitStatus()

  @Serializable
  @SerialName("HANDLED")
  data class TransitHandled(val transitId: String) : TransitStatus() {
    companion object {
      val example = TransitHandled("TR-001")
    }
  }
}

@Serializable
data class CollidingSealedContainerDto(
    val importStatus: ImportStatus,
    val transitStatus: TransitStatus,
) {
  companion object {
    val example =
        CollidingSealedContainerDto(
            ImportStatus.ImportHandled.example,
            TransitStatus.TransitHandled.example,
        )
  }
}

// --- Nullable sealed, List<Sealed>, Map<String, Sealed> ---

@Serializable
data class NullableSealedDto(
    val name: String,
    val status: SealedBase?,
) {
  companion object {
    val example = NullableSealedDto("test", SealedChild1.example)
  }
}

@Serializable
data class SealedListDto(
    val items: List<SealedBase>,
) {
  companion object {
    val example = SealedListDto(listOf(SealedChild1.example, SealedChild2.example))
  }
}

@Serializable
data class SealedMapDto(
    val statuses: Map<String, SealedBase>,
) {
  companion object {
    val example = SealedMapDto(mapOf("first" to SealedChild1.example))
  }
}

// --- Sealed parent with @SerialName ---

@Serializable
@SerialName("custom_event")
sealed class CustomNamedSealedParent {
  @Serializable
  @SerialName("started")
  data class Started(val at: String) : CustomNamedSealedParent() {
    companion object {
      val example = Started("2024-01-01")
    }
  }

  @Serializable
  @SerialName("completed")
  data class Completed(val at: String, val result: String) : CustomNamedSealedParent() {
    companion object {
      val example = Completed("2024-01-02", "success")
    }
  }
}

@Serializable
data class CustomNamedSealedContainerDto(
    val event: CustomNamedSealedParent,
) {
  companion object {
    val example = CustomNamedSealedContainerDto(CustomNamedSealedParent.Started.example)
  }
}

// --- @SerialName on property + sealed parent with @SerialName ---

@Serializable
data class SerialNamedPropertyWithSealedDto(
    @SerialName("event_payload") val event: CustomNamedSealedParent,
) {
  companion object {
    val example = SerialNamedPropertyWithSealedDto(CustomNamedSealedParent.Started.example)
  }
}

// --- Byte, Short, Char fields ---

@Serializable
data class ByteShortCharDto(
    val byteField: Byte,
    val shortField: Short,
    val charField: Char,
) {
  companion object {
    val example = ByteShortCharDto(127, 32000, 'A')
  }
}
