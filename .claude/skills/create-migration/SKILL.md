---
name: create-migration
description: Create a new Flyway migration file with auto-incrementing version number
disable-model-invocation: true
allowed-tools: Read, Write, Glob, Grep, Bash
---

Create a Flyway SQL migration for: $ARGUMENTS

## Current Migrations

!`ls infra-db/src/main/resources/db/migration/`

## Rules

1. Find the highest existing version number from migration filenames
2. Create the new file as `V{N+1}__<snake_case_description>.sql` in `infra-db/src/main/resources/db/migration/`
3. Start the file with a comment header: `-- <description of what this migration does>`
4. Write valid PostgreSQL 16 SQL
5. Use `IF NOT EXISTS` / `IF EXISTS` guards where appropriate
6. Add `COMMENT ON` for new tables/columns
7. **Never modify existing migration files** — they are immutable once applied

## Reference: Existing Schema

The `documents` table schema is defined in V1. Read it if you need to reference existing columns:
`infra-db/src/main/resources/db/migration/V1__create_documents_table.sql`

## After Creating

Report the full path and contents of the new migration file.
