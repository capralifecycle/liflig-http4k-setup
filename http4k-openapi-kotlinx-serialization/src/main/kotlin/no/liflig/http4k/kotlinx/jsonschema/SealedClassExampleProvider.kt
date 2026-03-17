package no.liflig.http4k.kotlinx.jsonschema

import kotlin.reflect.KClass
import kotlin.reflect.full.*

interface SealedClassExampleProvider {
  fun getExamples(sealedClass: KClass<*>): List<Any>
}

class DefaultSealedClassExampleProvider : SealedClassExampleProvider {
  override fun getExamples(sealedClass: KClass<*>): List<Any> {
    return sealedClass.sealedSubclasses.mapNotNull { subclass ->
      subclass.objectInstance
          ?: run {
            val companion = subclass.companionObjectInstance ?: return@mapNotNull null
            try {
              val exampleProperty = companion::class.memberProperties.find { it.name == "example" }
              exampleProperty?.getter?.call(companion)
            } catch (e: Exception) {
              null
            }
          }
    }
  }
}
