# Task 2 Report

## Status

Complete. The catalog/domain implementation commit is `6512bf6` (`feat: add current card catalog and deck validation`). The report is committed separately because the implementation commit hash is required in this report.

## Focused TDD verification

RED command (the requested shell form was unavailable on Windows):

```text
./gradlew test --tests org.aincraft.mobmatrix.domain.CardCatalogTest --tests org.aincraft.mobmatrix.domain.DeckValidationTest
```

Output: could not execute (`%1 is not a valid Win32 application`, because `gradlew` is a POSIX shell script on this workstation).

Equivalent Windows command, before implementation:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.domain.CardCatalogTest --tests org.aincraft.mobmatrix.domain.DeckValidationTest
```

Output: `:compileTestJava FAILED` with missing domain-type errors (76 errors), as expected before production classes existed.

GREEN command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.domain.CardCatalogTest --tests org.aincraft.mobmatrix.domain.DeckValidationTest
```

Output:

```text
BUILD SUCCESSFUL in 5s
4 actionable tasks: 4 executed
```

No formatter, linter, or project-wide suite was run.

## Catalog counts and provenance

- CSV data rows: **475**
- Explicit public numbers: **460** (`No. 1` through `No. 460`)
- Expansion public numbers: **15** (`Ex. 1` through `Ex. 15`)
- Unique internal IDs: **475**, sorted ascending in the CSV
- CSV columns: `id,public_number,name,stars,type,top,right,bottom,left,sale_price`
- CSV SHA-256: `043f52de103b9c7deb08b1781addff6eec84e4cebdee8daa3cbd106710e52588`
- Source endpoint: `https://ffxivcollect.com/api/triad/cards`
- Retrieval timestamp and upstream patch list: recorded in `src/main/resources/catalog/source.txt`
- Descriptions, image URLs, icon URLs, and artwork: not stored

## Concerns

- The upstream API acceptable-use terms are non-commercial; `source.txt` records that restriction and attribution links.
- The importer intentionally validates the current endpoint's exact 475-row public-number set; an upstream catalog change must be reviewed through a refreshed snapshot rather than silently accepted.

## Review-fix verification

### Gap test RED

Command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.domain.CardCatalogTest --tests org.aincraft.mobmatrix.domain.DeckValidationTest
```

Output:

```text
CardCatalogTest > rejectsGapsInInternalIdsWithinSuppliedCollection() FAILED
    org.opentest4j.AssertionFailedError at CardCatalogTest.java:27

8 tests completed, 1 failed
BUILD FAILED in 1s
```

The failure was the expected assertion failure before the contiguous-ID implementation.

### Review-fix GREEN

Command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.domain.CardCatalogTest --tests org.aincraft.mobmatrix.domain.DeckValidationTest
```

Output:

```text
> Task :compileJava
> Task :processResources
> Task :classes
> Task :compileTestJava
> Task :processTestResources NO-SOURCE
> Task :testClasses
> Task :test

BUILD SUCCESSFUL in 1s
4 actionable tasks: 4 executed
```

### Importer syntax

Command:

```text
node --check tools/refresh-cards.js
```

Output: no output; command exited successfully.

## Review fixes

- `CardCatalog` now rejects the first missing internal ID in the sorted supplied collection while retaining the valid single-definition ID 7 fixture.
- `currentSnapshotContainsAllPublicNumbers` now asserts exact set equality for `No. 1` through `No. 460` and `Ex. 1` through `Ex. 15`.
- FFXIV Collect acceptable-use references now use `https://ffxivcollect.com/api/docs` in both provenance and importer output.
- Code/data commit: `486aac7` (`fix: validate catalog completeness`).
