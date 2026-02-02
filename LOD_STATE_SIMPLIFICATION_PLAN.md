# LOD State Simplification Plan

## Design Decisions

### Core Principles

1. **Only write complete sections to database.** Incomplete sections exist only in memory during accumulation. This eliminates any need to track or store information about incomplete/partially completed generation steps.

2. **`isFullyGenerated()` becomes redundant.** Since we only operate on complete objects:
   - Database existence = complete section
   - In-memory objects are either accumulating (not saved) or complete (saved)
   - No need to query generation state - presence in database IS the state

3. **No backwards compatibility.** We will only test with fresh databases. No migration path needed for old columnGenerationSteps data.

4. **Remove all columnGenerationSteps infrastructure.** Drop database column, remove from code, rewrite queries.

---

## Implementation Phases

### Phase 1: Remove Downward Propagation

Eliminates the DOWN_SAMPLED state entirely.

**Files to modify:**
- `FullDataUpdatePropagatorV2.java`: Remove `runChildUpdates()` method and config check
- `FullDataSourceV2.java`: Remove `downsampleFromOneAboveDetailLevel()` method
- `Config.java`: Remove `upsampleLowerDetailLodsToFillHoles` config
- `en_us.json`: Remove translation strings

**Result:** DOWN_SAMPLED can never be created

---

### Phase 2: Only Save Complete Sections

Modify the save logic to only persist sections when all 4096 columns have data.

**Files to modify:**
- `DelayedFullDataSourceSaveCache.java`: Add completion check before save
- `FullDataUpdaterV2.java`: Guard save with completion check
- `FullDataSourceV2.java`: Add `isComplete()` method checking `dataPoints[i] != null` for all i

**Result:** Database only contains complete sections

---

### Phase 3: Remove columnGenerationSteps from Database

**SQL Schema change:**
```sql
-- Remove from table definition
-- ColumnGenerationStep BLOB NULL  -- DELETE
```

**Files to modify:**
- `0020-sqlite-createFullDataSourceV2Tables.sql`: Remove ColumnGenerationStep column
- `FullDataSourceV2Repo.java`: Remove all ColumnGenerationStep handling from queries
- `FullDataSourceV2DTO.java`: Remove serialization/deserialization of generation steps
- Remove `getColumnGenerationStepForPos()` method entirely

---

### Phase 4: Replace Generation Checks with Existence Checks

**Files to modify:**
- `GeneratedFullDataSourceProvider.java`:
  - Remove `isFullyGenerated(ByteArrayList)` method
  - Replace calls with `repo.existsWithKey(pos)`
  - Simplify `getPositionsToRetrieve()` to query for missing positions
- `PregenManager.java`: Replace `isFullyGenerated()` call
- `FullDataSourceRequestHandler.java`: Replace `isFullyGenerated()` call
- `LodRenderSection.java`: Replace `isFullyGenerated()` / `getMissingGenerationPos()` calls
- `LodQuadTree.java`: Replace generation checks

---

### Phase 5: Remove columnGenerationSteps from FullDataSourceV2

**Files to modify:**
- `FullDataSourceV2.java`:
  - Remove `columnGenerationSteps` field
  - Simplify merge logic: `if (inputDataArray != null)` accept it
  - Remove `determineMinWorldGenStepForTwoByTwoColumn()` method
  - Remove all generation step comparisons in `updateFromSameDetailLevel()`
  - Remove all generation step handling in `updateFromOneBelowDetailLevel()`

---

### Phase 6: Remove applyToChildren Flag

Since downward propagation is removed, this flag is dead.

**Files to modify:**
- `FullDataSourceV2.java`: Remove `applyToChildren` field
- `FullDataSourceV2DTO.java`: Remove `applyToChildren` serialization
- `FullDataSourceV2Repo.java`: Remove from queries
- `0020-sqlite-createFullDataSourceV2Tables.sql`: Remove ApplyToChildren column

---

### Phase 7: Simplify Generation Step Enum

**Files to modify:**
- `EDhApiWorldGenerationStep.java`: Remove STRUCTURE_START, STRUCTURE_REFERENCE, BIOMES, NOISE, DOWN_SAMPLED
- Keep only: EMPTY (0), LIGHT (9) - or consider removing enum entirely

---

## Summary

| What | Action |
|------|--------|
| Partial sections in DB | Never written (only complete) |
| `isFullyGenerated()` | Replaced with `existsWithKey()` |
| `columnGenerationSteps` | Removed entirely |
| `ColumnGenerationStep` column | Dropped from schema |
| `applyToChildren` | Removed (no downward propagation) |
| Backwards compatibility | None - fresh databases only |

---

## Estimated Removal

| Component | Lines |
|-----------|-------|
| `downsampleFromOneAboveDetailLevel()` | ~90 |
| `runChildUpdates()` | ~120 |
| columnGenerationSteps field + methods | ~200 |
| DTO serialization | ~50 |
| Generation step checks | ~100 |
| Config options | ~20 |
| **Total** | **~580 lines** |
