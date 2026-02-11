---
name: implement-stub
description: Implement a TODO() stub following project patterns and domain contracts
disable-model-invocation: true
allowed-tools: Read, Write, Edit, Glob, Grep, Bash
---

Implement the TODO() stub in: $ARGUMENTS

## Discovery

1. Read the target file containing the TODO() stub
2. Identify which domain interface the class implements (check `core-domain/src/main/kotlin/org/example/pipeline/domain/`)
3. Read that interface to understand the full contract (parameter types, return types, semantics)
4. Read any sibling methods already implemented in the same file for pattern consistency

## All TODO Stubs

!`grep -rn "TODO(" --include="*.kt" .`

## Implementation Rules

- **Exposed DSL only** — use `select`/`insert`/`update`/`deleteWhere`, not DAO entities
- **`kotlinx.datetime.Instant`** for all timestamps, never `java.time`
- **`kotlinx.serialization`** for any JSON — classes need `@Serializable`
- **Suspend functions** must use appropriate dispatchers (`Dispatchers.IO` for file/network I/O)
- **`newSuspendedTransaction`** from Exposed for coroutine-safe DB access
- **`import kotlin.time.Clock`** for `Clock.System` (not `kotlinx.datetime.Clock`)
- **Avoid Pair/Triple destructuring in lambdas** — use data classes or `.first`/`.second`
- Match the logging style already present in the file (`logger.info`/`logger.error`)
- Follow the hints in the TODO() string — they describe the expected implementation

## After Implementing

1. Determine which module the file belongs to
2. Run `./gradlew :<module>:compileKotlin --quiet` to verify compilation
3. Run `./gradlew :<module>:test` if tests exist for that module
4. Report what was implemented and any test results
