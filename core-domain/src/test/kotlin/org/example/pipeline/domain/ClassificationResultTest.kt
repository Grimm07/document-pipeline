package org.example.pipeline.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.map
import io.kotest.property.forAll
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ClassificationResultTest : FunSpec({

    val json = Json { encodeDefaults = true }

    context("construction") {
        test("valid at confidence 0.0") {
            val result = ClassificationResult("invoice", 0.0f)
            result.confidence shouldBe 0.0f
        }

        test("valid at confidence 0.5") {
            val result = ClassificationResult("receipt", 0.5f)
            result.confidence shouldBe 0.5f
        }

        test("valid at confidence 1.0") {
            val result = ClassificationResult("contract", 1.0f)
            result.confidence shouldBe 1.0f
        }
    }

    context("validation") {
        test("rejects confidence below 0.0") {
            val ex = shouldThrow<IllegalArgumentException> {
                ClassificationResult("invoice", -0.1f)
            }
            ex.message shouldContain "Confidence must be between 0.0 and 1.0"
        }

        test("rejects confidence above 1.0") {
            val ex = shouldThrow<IllegalArgumentException> {
                ClassificationResult("invoice", 1.1f)
            }
            ex.message shouldContain "Confidence must be between 0.0 and 1.0"
        }

        test("rejects NaN confidence") {
            shouldThrow<IllegalArgumentException> {
                ClassificationResult("invoice", Float.NaN)
            }
        }
    }

    context("serialization") {
        test("round-trip with typical values") {
            val result = ClassificationResult("invoice", 0.87f)
            val serialized = json.encodeToString(result)
            val deserialized = json.decodeFromString<ClassificationResult>(serialized)
            deserialized shouldBe result
        }

        test("round-trip at boundary values") {
            val low = ClassificationResult("unknown", 0.0f)
            val high = ClassificationResult("certain", 1.0f)

            json.decodeFromString<ClassificationResult>(json.encodeToString(low)) shouldBe low
            json.decodeFromString<ClassificationResult>(json.encodeToString(high)) shouldBe high
        }
    }

    context("property-based") {
        test("accepts any float in 0.0..1.0") {
            forAll(Arb.float(0.0f, 1.0f).filter { !it.isNaN() }) { confidence ->
                val result = ClassificationResult("test", confidence)
                result.confidence == confidence
            }
        }

        test("rejects floats greater than 1.0") {
            forAll(Arb.float().filter { it > 1.0f && !it.isNaN() }) { confidence ->
                try {
                    ClassificationResult("test", confidence)
                    false
                } catch (_: IllegalArgumentException) {
                    true
                }
            }
        }

        test("rejects negative floats") {
            forAll(Arb.float().filter { it < 0.0f && !it.isNaN() }) { confidence ->
                try {
                    ClassificationResult("test", confidence)
                    false
                } catch (_: IllegalArgumentException) {
                    true
                }
            }
        }
    }
})
