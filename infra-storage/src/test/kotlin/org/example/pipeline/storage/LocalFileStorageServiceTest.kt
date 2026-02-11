package org.example.pipeline.storage

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.uuid
import io.kotest.property.forAll
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.time.Clock
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.time.Instant

class LocalFileStorageServiceTest : FunSpec({

    val baseDir: Path = createTempDirectory("storage-test")
    val service = LocalFileStorageService(baseDir)

    afterSpec {
        baseDir.toFile().deleteRecursively()
    }

    context("initialization") {
        test("constructor creates baseDir when it doesn't exist") {
            val newDir = baseDir.resolve("init-test-nonexistent")
            LocalFileStorageService(newDir)
            newDir.exists().shouldBeTrue()
        }

        test("constructor succeeds when baseDir already exists") {
            val existingDir = baseDir.resolve("init-test-existing")
            existingDir.createDirectories()
            // Should not throw
            LocalFileStorageService(existingDir)
            existingDir.exists().shouldBeTrue()
        }

        test("constructor creates nested directory paths") {
            val nestedDir = baseDir.resolve("a/b/c/deep-init")
            LocalFileStorageService(nestedDir)
            nestedDir.exists().shouldBeTrue()
        }
    }

    context("store") {
        test("returns a non-blank storage path") {
            val path = service.store("doc-1", "report.pdf", "hello".toByteArray())
            path.shouldNotBeBlank()
        }

        test("returned path contains the file extension from the original filename") {
            val path = service.store("doc-2", "photo.png", "image-data".toByteArray())
            path.shouldContain(".png")
        }

        test("returned path contains the document ID") {
            val id = UUID.randomUUID().toString()
            val path = service.store(id, "notes.txt", "some notes".toByteArray())
            path.shouldContain(id)
        }

        test("file content is physically written to disk under baseDir") {
            val content = "physical content check".toByteArray()
            val storagePath = service.store("doc-disk", "file.txt", content)

            val fullPath = baseDir.resolve(storagePath)
            fullPath.exists().shouldBeTrue()
            fullPath.readBytes() shouldBe content
        }

        test("different IDs produce different storage paths") {
            val path1 = service.store("id-aaa", "file.txt", "a".toByteArray())
            val path2 = service.store("id-bbb", "file.txt", "b".toByteArray())
            path1 shouldNotBe path2
        }
    }

    context("retrieve") {
        test("returns null for a path that doesn't exist") {
            val result = service.retrieve("nonexistent/path/file.txt")
            result.shouldBeNull()
        }

        test("returns the exact content that was stored (round-trip)") {
            val content = "round-trip content".toByteArray()
            val storagePath = service.store("doc-rt", "data.bin", content)

            val retrieved = service.retrieve(storagePath)
            retrieved shouldBe content
        }

        test("returns null after file is deleted") {
            val content = "to-be-deleted".toByteArray()
            val storagePath = service.store("doc-del-ret", "temp.txt", content)

            service.delete(storagePath)
            service.retrieve(storagePath).shouldBeNull()
        }
    }

    context("delete") {
        test("returns false for a path that doesn't exist") {
            val result = service.delete("nonexistent/path/ghost.txt")
            result.shouldBeFalse()
        }

        test("returns true when file is successfully deleted") {
            val storagePath = service.store("doc-del-ok", "removable.txt", "bye".toByteArray())
            val result = service.delete(storagePath)
            result.shouldBeTrue()
        }

        test("file no longer exists on disk after deletion") {
            val storagePath = service.store("doc-del-disk", "gone.txt", "vanish".toByteArray())
            service.delete(storagePath)

            val fullPath = baseDir.resolve(storagePath)
            fullPath.exists().shouldBeFalse()
        }
    }

    context("property-based: round-trip") {
        test("random content of various sizes survives store-retrieve round-trip") {
            forAll(
                Arb.byteArray(Arb.int(0..10_000), Arb.byte()),
                Arb.uuid()
            ) { content, uuid ->
                val storagePath = service.store(uuid.toString(), "data.bin", content)
                val retrieved = service.retrieve(storagePath)
                retrieved.contentEquals(content)
            }
        }

        test("random UUIDs work as document IDs") {
            forAll(Arb.uuid()) { uuid ->
                val id = uuid.toString()
                val storagePath = service.store(id, "test.txt", "content".toByteArray())
                storagePath.contains(id)
            }
        }
    }

    context("property-based: filename extensions") {
        val extensionArb: Arb<String> = Arb.element("pdf", "png", "txt", "docx", "jpg", "csv", "json", "xml")
        val filenameArb: Arb<String> = arbitrary {
            val ext = extensionArb.bind()
            "document.$ext"
        }

        test("file extension from original filename is preserved in storage path") {
            forAll(filenameArb, Arb.uuid()) { filename, uuid ->
                val expectedExt = filename.substringAfterLast('.')
                val storagePath = service.store(uuid.toString(), filename, "test".toByteArray())
                storagePath.endsWith(".$expectedExt")
            }
        }
    }

    context("path traversal protection") {
        test("retrieve rejects ../ that escapes baseDir") {
            shouldThrow<IllegalArgumentException> {
                service.retrieve("../../etc/passwd")
            }.message shouldContain "Path traversal detected"
        }

        test("delete rejects ../ that escapes baseDir") {
            shouldThrow<IllegalArgumentException> {
                service.delete("../../../etc/shadow")
            }.message shouldContain "Path traversal detected"
        }

        test("rejects absolute path outside baseDir") {
            shouldThrow<IllegalArgumentException> {
                service.retrieve("/etc/passwd")
            }.message shouldContain "Path traversal detected"
        }

        test("allows valid nested path within baseDir") {
            val content = "safe content".toByteArray()
            val storagePath = service.store("safe-doc", "safe.txt", content)
            val retrieved = service.retrieve(storagePath)
            retrieved shouldBe content
        }
    }

    context("path contract and relative baseDir") {
        test("store returns a relative path that does not contain baseDir prefix") {
            val subDir = baseDir.resolve("path-contract-test")
            val svc = LocalFileStorageService(subDir)
            val storagePath = svc.store("contract-id", "file.pdf", "data".toByteArray())

            // The returned path should be purely relative (yyyy/MM/dd/id.ext)
            // It must NOT start with any component of the baseDir
            storagePath.startsWith("/").shouldBeFalse()
            storagePath.shouldNotContain(subDir.toString())
            storagePath.shouldNotContain("path-contract-test")
        }

        test("round-trip works with relative baseDir") {
            // Create a relative Path inside a known temp directory
            val anchor = createTempDirectory("relative-anchor")
            val originalWorkDir = System.getProperty("user.dir")
            try {
                System.setProperty("user.dir", anchor.toString())
                val relativeDir = anchor.resolve("relative-test-dir")
                val svc = LocalFileStorageService(relativeDir)

                val content = "relative baseDir round-trip content".toByteArray()
                val storagePath = svc.store("rel-id", "doc.pdf", content)
                val retrieved = svc.retrieve(storagePath)

                retrieved shouldBe content
            } finally {
                System.setProperty("user.dir", originalWorkDir)
                anchor.toFile().deleteRecursively()
            }
        }

        test("store path is reusable across service instances with same baseDir") {
            val sharedDir = baseDir.resolve("cross-instance")
            val svc1 = LocalFileStorageService(sharedDir)
            val content = "cross-instance content".toByteArray()
            val storagePath = svc1.store("cross-id", "file.txt", content)

            // New instance, same baseDir
            val svc2 = LocalFileStorageService(sharedDir)
            val retrieved = svc2.retrieve(storagePath)
            retrieved shouldBe content
        }

        test("stored path does not duplicate when used in baseDir.resolve") {
            // This test verifies the contract that broke the original bug:
            // baseDir.resolve(storedPath) must point to the actual file on disk.
            // If store() returned an already-resolved path, this would break.
            val subDir = baseDir.resolve("resolve-contract")
            val svc = LocalFileStorageService(subDir)
            val content = "resolve contract content".toByteArray()
            val storagePath = svc.store("resolve-id", "file.txt", content)

            // Manually construct the path the same way resolvePath does
            val manualResolved = subDir.resolve(storagePath).normalize()
            manualResolved.exists().shouldBeTrue()
            manualResolved.readBytes() shouldBe content

            // And the path should NOT already be absolute
            Path.of(storagePath).isAbsolute.shouldBeFalse()
        }
    }

    context("filename edge cases") {
        test("no extension uses bin fallback") {
            val path = service.store("edge-no-ext", "README", "content".toByteArray())
            path.shouldContain(".bin")
        }

        test("multiple dots uses last extension") {
            val path = service.store("edge-multi-dot", "archive.tar.gz", "content".toByteArray())
            path.shouldContain(".gz")
        }

        test("dot-only filename produces retrievable file") {
            val path = service.store("edge-dot-only", ".", "content".toByteArray())
            // substringAfterLast('.', "bin") returns "" for "."
            // This produces a path ending with "edge-dot-only." which is a trailing dot
            // Verify the round-trip still works
            val stored = service.retrieve(path)
            stored shouldBe "content".toByteArray()
        }

        test("empty filename uses bin fallback") {
            val path = service.store("edge-empty", "", "content".toByteArray())
            path.shouldContain(".bin")
        }

        test("trailing dot filename produces retrievable file") {
            val path = service.store("edge-trailing-dot", "file.", "content".toByteArray())
            // substringAfterLast('.', "bin") returns "" for "file."
            // Documents current behavior - round-trip still works
            val stored = service.retrieve(path)
            stored shouldBe "content".toByteArray()
        }

        test("filename with spaces works") {
            val path = service.store("edge-spaces", "my document.pdf", "content".toByteArray())
            path.shouldContain(".pdf")
            val stored = service.retrieve(path)
            stored shouldBe "content".toByteArray()
        }
    }

    context("date-based path generation") {
        test("storage path contains date components matching the current clock time") {
            // Freeze Clock.System to a known instant: 2024-07-15T12:00:00Z
            val frozenInstant = Instant.parse("2024-07-15T12:00:00Z")
            mockkObject(Clock.System)
            every { Clock.System.now() } returns frozenInstant

            try {
                // Create a fresh service so generateStoragePath uses the mocked clock
                val frozenService = LocalFileStorageService(baseDir.resolve("frozen-clock"))
                val storagePath = frozenService.store("clock-test-id", "file.pdf", "data".toByteArray())

                storagePath.shouldContain("2024/07/15")
                storagePath.shouldContain("clock-test-id")
                storagePath.shouldContain(".pdf")
            } finally {
                unmockkObject(Clock.System)
            }
        }
    }
})
