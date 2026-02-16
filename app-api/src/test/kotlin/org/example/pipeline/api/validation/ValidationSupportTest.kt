package org.example.pipeline.api.validation

import io.konform.validation.Validation
import io.konform.validation.constraints.maxLength
import io.konform.validation.constraints.minimum
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe

private data class TestInput(val name: String, val age: Int)

class ValidationSupportTest : FunSpec({

    val testValidator = Validation<TestInput> {
        TestInput::name {
            maxLength(10)
        }
        TestInput::age {
            minimum(0)
        }
    }

    test("validate returns value when validation passes") {
        val input = TestInput("Alice", 25)
        val result = shouldNotThrowAny { input.validate(testValidator) }
        result shouldBe input
    }

    test("validate throws ValidationException on failure") {
        val input = TestInput("A very long name that exceeds", -1)
        val ex = shouldThrow<ValidationException> { input.validate(testValidator) }
        ex.fieldErrors.size shouldBe 2
        ex.fieldErrors shouldContainKey ".name"
        ex.fieldErrors shouldContainKey ".age"
    }

    test("fieldErrors contains correct messages for single violation") {
        val input = TestInput("A very long name that exceeds", 5)
        val ex = shouldThrow<ValidationException> { input.validate(testValidator) }
        ex.fieldErrors.size shouldBe 1
        ex.fieldErrors shouldContainKey ".name"
    }

    test("ValidationException message is Validation failed") {
        val input = TestInput("toolong12345", -1)
        val ex = shouldThrow<ValidationException> { input.validate(testValidator) }
        ex.message shouldBe "Validation failed"
    }
})
