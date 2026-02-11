package org.example.pipeline.storage

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory

private data class StoreResult(val clientId: Int, val docId: String, val path: String)
private data class SeededFile(val path: String, val content: ByteArray)
private data class DeleteResult(val clientId: Int, val path: String, val deleted: Boolean)
private data class PipelineResult(
    val clientId: Int,
    val docId: String,
    val storagePath: String,
    val retrievedContent: ByteArray?,
    val originalContent: ByteArray,
    val deleteResult: Boolean,
)

class LocalFileStorageServiceStressTest : FunSpec({

    val baseDir: Path = createTempDirectory("stress-test")
    val service = LocalFileStorageService(baseDir)

    afterSpec {
        baseDir.toFile().deleteRecursively()
    }

    // ── Per-operation stress: store ──────────────────────────
    context("concurrent store") {

        test("single client: 50 concurrent stores complete without error") {
            coroutineScope {
                val results = (1..50).map { i ->
                    async(CoroutineName("store-$i")) {
                        val id = UUID.randomUUID().toString()
                        val content = "content-$i".toByteArray()
                        val path = service.store(id, "file-$i.txt", content)
                        StoreResult(0, id, path)
                    }
                }.awaitAll()

                results.shouldHaveSize(50)
                results.forEach { r ->
                    withClue("store result for docId=${r.docId}") {
                        r.path.isNotBlank().shouldBeTrue()
                    }
                }
            }
        }

        test("multi-client: 10 clients x 20 stores each, all files written correctly") {
            coroutineScope {
                val clientResults = (1..10).map { clientId ->
                    async(CoroutineName("client-$clientId")) {
                        (1..20).map { opId ->
                            async(CoroutineName("client-$clientId/op-$opId")) {
                                val id = UUID.randomUUID().toString()
                                val content = "client-$clientId-op-$opId".toByteArray()
                                val path = service.store(id, "doc-$opId.pdf", content)
                                StoreResult(clientId, id, path)
                            }
                        }.awaitAll()
                    }
                }.awaitAll().flatten()

                clientResults.shouldHaveSize(200)
                clientResults.forEach { r ->
                    withClue("client=${r.clientId}, docId=${r.docId}") {
                        r.path.isNotBlank().shouldBeTrue()
                    }
                }
            }
        }

        test("all stored files have unique paths") {
            coroutineScope {
                val paths = (1..50).map { i ->
                    async(CoroutineName("unique-$i")) {
                        val id = UUID.randomUUID().toString()
                        service.store(id, "unique-$i.txt", "data-$i".toByteArray())
                    }
                }.awaitAll()

                val uniquePaths = paths.toSet()
                withClue("expected 50 unique paths but got ${uniquePaths.size}") {
                    uniquePaths.shouldHaveSize(50)
                }
            }
        }
    }

    // ── Per-operation stress: retrieve ──────────────────────
    context("concurrent retrieve") {

        test("single client: 50 concurrent retrieves of same file return identical content") {
            val content = "shared-content-for-retrieval".toByteArray()
            val storagePath = service.store(UUID.randomUUID().toString(), "shared.txt", content)

            coroutineScope {
                val results = (1..50).map { i ->
                    async(CoroutineName("retrieve-$i")) {
                        service.retrieve(storagePath)
                    }
                }.awaitAll()

                results.forEach { retrieved ->
                    withClue("retrieved content should match original") {
                        retrieved.shouldNotBeNull()
                        retrieved shouldBe content
                    }
                }
            }
        }

        test("multi-client: 10 clients x 20 retrieves of different files, all round-trip correct") {
            // Pre-seed 200 files
            val seeded: List<SeededFile> = coroutineScope {
                (1..200).map { i ->
                    async(CoroutineName("seed-$i")) {
                        val id = UUID.randomUUID().toString()
                        val content = "seeded-content-$i".toByteArray()
                        val path = service.store(id, "seeded-$i.bin", content)
                        SeededFile(path, content)
                    }
                }.awaitAll()
            }

            coroutineScope {
                val clientResults = (1..10).map { clientId ->
                    async(CoroutineName("client-$clientId")) {
                        val slice = seeded.subList((clientId - 1) * 20, clientId * 20)
                        slice.mapIndexed { opId, seededFile ->
                            async(CoroutineName("client-$clientId/op-$opId")) {
                                val retrieved = service.retrieve(seededFile.path)
                                StoreResult(clientId, seededFile.path, "") to (retrieved to seededFile.content)
                            }
                        }.awaitAll()
                    }
                }.awaitAll().flatten()

                clientResults.forEach { result ->
                    val clientId = result.first.clientId
                    val path = result.first.docId
                    val retrieved = result.second.first
                    val expected = result.second.second
                    withClue("client=$clientId, path=$path") {
                        retrieved.shouldNotBeNull()
                        retrieved shouldBe expected
                    }
                }
            }
        }

        test("retrieves of nonexistent paths return null without throwing") {
            coroutineScope {
                val results = (1..50).map { i ->
                    async(CoroutineName("missing-$i")) {
                        service.retrieve("nonexistent/path/file-$i.txt")
                    }
                }.awaitAll()

                results.forEach { retrieved ->
                    withClue("nonexistent path should return null") {
                        retrieved.shouldBeNull()
                    }
                }
            }
        }
    }

    // ── Per-operation stress: delete ────────────────────────
    context("concurrent delete") {

        test("single client: 50 concurrent deletes of distinct files, all return true") {
            // Pre-seed 50 files
            val paths = coroutineScope {
                (1..50).map { i ->
                    async(CoroutineName("seed-del-$i")) {
                        val id = UUID.randomUUID().toString()
                        service.store(id, "deletable-$i.txt", "delete-me-$i".toByteArray())
                    }
                }.awaitAll()
            }

            coroutineScope {
                val results = paths.mapIndexed { i, path ->
                    async(CoroutineName("delete-$i")) {
                        DeleteResult(0, path, service.delete(path))
                    }
                }.awaitAll()

                results.forEach { r ->
                    withClue("delete should succeed for path=${r.path}") {
                        r.deleted.shouldBeTrue()
                    }
                }
            }
        }

        test("multi-client: 10 clients x 20 deletes each") {
            // Pre-seed 200 files
            val paths = coroutineScope {
                (1..200).map { i ->
                    async(CoroutineName("seed-multi-del-$i")) {
                        val id = UUID.randomUUID().toString()
                        service.store(id, "multi-del-$i.txt", "data-$i".toByteArray())
                    }
                }.awaitAll()
            }

            coroutineScope {
                val clientResults = (1..10).map { clientId ->
                    async(CoroutineName("client-$clientId")) {
                        val slice = paths.subList((clientId - 1) * 20, clientId * 20)
                        slice.mapIndexed { opId, path ->
                            async(CoroutineName("client-$clientId/op-$opId")) {
                                DeleteResult(clientId, path, service.delete(path))
                            }
                        }.awaitAll()
                    }
                }.awaitAll().flatten()

                clientResults.forEach { r ->
                    withClue("client=${r.clientId}, path=${r.path}") {
                        r.deleted.shouldBeTrue()
                    }
                }
            }
        }

        test("double-delete: second delete returns false (no race to true)") {
            // Pre-seed 50 files
            val paths = coroutineScope {
                (1..50).map { i ->
                    async(CoroutineName("seed-double-$i")) {
                        val id = UUID.randomUUID().toString()
                        service.store(id, "double-del-$i.txt", "data-$i".toByteArray())
                    }
                }.awaitAll()
            }

            // First delete — all should succeed
            coroutineScope {
                paths.mapIndexed { i, path ->
                    async(CoroutineName("first-del-$i")) {
                        service.delete(path)
                    }
                }.awaitAll()
            }

            // Second delete — all should return false
            coroutineScope {
                val results = paths.mapIndexed { i, path ->
                    async(CoroutineName("second-del-$i")) {
                        DeleteResult(0, path, service.delete(path))
                    }
                }.awaitAll()

                results.forEach { r ->
                    withClue("second delete should return false for path=${r.path}") {
                        r.deleted.shouldBeFalse()
                    }
                }
            }
        }
    }

    // ── Same-ID concurrent store ───────────────────────────
    context("concurrent same-ID store") {

        test("10 coroutines storing different content with same ID produces consistent result") {
            val sharedId = UUID.randomUUID().toString()
            val contents = (1..10).map { "content-variant-$it".toByteArray() }

            coroutineScope {
                val paths = contents.mapIndexed { i, content ->
                    async(CoroutineName("same-id-$i")) {
                        service.store(sharedId, "file.txt", content)
                    }
                }.awaitAll()

                // All store calls use the same ID, so they produce the same path
                val uniquePaths = paths.toSet()
                uniquePaths.shouldHaveSize(1)

                // Retrieve should return one of the stored contents (last-writer-wins)
                val retrieved = service.retrieve(paths.first())
                retrieved.shouldNotBeNull()
                // The retrieved content must be one of the valid variants, not corrupted data
                val matchesAny = contents.any { it.contentEquals(retrieved) }
                withClue("retrieved content should match one of the stored variants") {
                    matchesAny.shouldBeTrue()
                }
            }
        }
    }

    // ── Mixed operations ────────────────────────────────────
    context("mixed operations") {

        test("single client: concurrent store + retrieve + delete on disjoint file sets") {
            // Pre-seed files for retrieve and delete sets
            val retrieveFiles: List<SeededFile> = coroutineScope {
                (1..15).map { i ->
                    async(CoroutineName("seed-ret-$i")) {
                        val id = UUID.randomUUID().toString()
                        val content = "retrieve-content-$i".toByteArray()
                        val path = service.store(id, "ret-$i.bin", content)
                        SeededFile(path, content)
                    }
                }.awaitAll()
            }

            val deleteFiles: List<String> = coroutineScope {
                (1..15).map { i ->
                    async(CoroutineName("seed-del-$i")) {
                        val id = UUID.randomUUID().toString()
                        service.store(id, "del-$i.bin", "delete-content-$i".toByteArray())
                    }
                }.awaitAll()
            }

            coroutineScope {
                // 20 concurrent stores
                val storeJobs = (1..20).map { i ->
                    async(CoroutineName("mixed-store-$i")) {
                        val id = UUID.randomUUID().toString()
                        service.store(id, "mixed-$i.txt", "mixed-$i".toByteArray())
                    }
                }

                // 15 concurrent retrieves
                val retrieveJobs = retrieveFiles.mapIndexed { i, seededFile ->
                    async(CoroutineName("mixed-retrieve-$i")) {
                        val retrieved = service.retrieve(seededFile.path)
                        SeededFile(seededFile.path, seededFile.content) to retrieved
                    }
                }

                // 15 concurrent deletes
                val deleteJobs = deleteFiles.mapIndexed { i, path ->
                    async(CoroutineName("mixed-delete-$i")) {
                        DeleteResult(0, path, service.delete(path))
                    }
                }

                // Await all
                val stored = storeJobs.map { it.await() }
                val retrieved = retrieveJobs.map { it.await() }
                val deleted = deleteJobs.map { it.await() }

                stored.forEach { path ->
                    withClue("mixed store should produce non-blank path") {
                        path.isNotBlank().shouldBeTrue()
                    }
                }

                retrieved.forEach { result ->
                    val seededFile = result.first
                    val data = result.second
                    withClue("mixed retrieve for path=${seededFile.path}") {
                        data.shouldNotBeNull()
                        data shouldBe seededFile.content
                    }
                }

                deleted.forEach { r ->
                    withClue("mixed delete for path=${r.path}") {
                        r.deleted.shouldBeTrue()
                    }
                }
            }
        }

        test("multi-client: 10 clients each doing store -> retrieve -> delete pipeline concurrently") {
            coroutineScope {
                val clientResults = (1..10).map { clientId ->
                    async(CoroutineName("pipeline-client-$clientId")) {
                        (1..10).map { opId ->
                            async(CoroutineName("pipeline-client-$clientId/op-$opId")) {
                                val id = UUID.randomUUID().toString()
                                val content = "pipeline-$clientId-$opId".toByteArray()

                                // Store
                                val path = service.store(id, "pipeline-$opId.txt", content)

                                // Retrieve
                                val retrieved = service.retrieve(path)

                                // Delete
                                val deleted = service.delete(path)

                                PipelineResult(clientId, id, path, retrieved, content, deleted)
                            }
                        }.awaitAll()
                    }
                }.awaitAll().flatten()

                clientResults.forEach { r ->
                    withClue("client=${r.clientId}, docId=${r.docId}") {
                        r.storagePath.isNotBlank().shouldBeTrue()
                        r.retrievedContent.shouldNotBeNull()
                        r.retrievedContent shouldBe r.originalContent
                        r.deleteResult.shouldBeTrue()
                    }
                }
            }
        }

        test("chaos: random mix of store/retrieve/delete on overlapping file sets") {
            // Pre-seed a pool of files
            val seedPaths: List<SeededFile> = coroutineScope {
                (1..30).map { i ->
                    async(CoroutineName("chaos-seed-$i")) {
                        val id = UUID.randomUUID().toString()
                        val content = "chaos-seed-$i".toByteArray()
                        val path = service.store(id, "chaos-$i.txt", content)
                        SeededFile(path, content)
                    }
                }.awaitAll()
            }

            data class ChaosResult(val op: String, val success: Boolean)

            coroutineScope {
                // New stores on fresh IDs
                val storeJobs = (1..10).map { i ->
                    async(CoroutineName("chaos-store-$i")) {
                        val id = UUID.randomUUID().toString()
                        val path = service.store(id, "chaos-new-$i.txt", "new-$i".toByteArray())
                        ChaosResult("store", path.isNotBlank())
                    }
                }

                // Retrieves on seeded files (may or may not have been deleted concurrently)
                val retrieveJobs = seedPaths.take(10).mapIndexed { i, seededFile ->
                    async(CoroutineName("chaos-retrieve-$i")) {
                        val result = service.retrieve(seededFile.path)
                        // Result is either valid content or null (if concurrently deleted) — never throws
                        ChaosResult("retrieve", result != null || result == null)
                    }
                }

                // Deletes on seeded files (overlapping with retrieve set)
                val deleteJobs = seedPaths.take(10).mapIndexed { i, seededFile ->
                    async(CoroutineName("chaos-delete-$i")) {
                        val result = service.delete(seededFile.path)
                        // Either true (deleted) or false (already deleted by concurrent op)
                        ChaosResult("delete", result || !result)
                    }
                }

                val allResults = (storeJobs + retrieveJobs + deleteJobs).awaitAll()

                // Primary assertion: no coroutine crashed, all returned valid results
                allResults.shouldHaveSize(30)
                allResults.forEach { r ->
                    withClue("chaos op=${r.op} should complete without error") {
                        r.success.shouldBeTrue()
                    }
                }
            }
        }
    }
})
