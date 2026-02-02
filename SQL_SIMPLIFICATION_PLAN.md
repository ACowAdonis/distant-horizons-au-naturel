# SQL Infrastructure Simplification Plan

## Overview

This fork does not support:
- Backward compatibility with older database formats
- Migration from older versions
- Multiple Minecraft versions
- Partial/incomplete LOD generation states

Therefore, the entire migration system is unnecessary. We should have a single schema creation script.

---

## Phase 1: Eliminate Migration System

**Priority: HIGH - Foundation for all other changes**

### Current State
- `DatabaseUpdater.java` runs migration scripts in order
- `scriptList.txt` lists 9 migration scripts
- Scripts create tables, alter them, add columns incrementally
- Complex logic tracks which scripts have run

### Target State
- Single `schema.sql` file that creates final schema
- Remove `DatabaseUpdater` migration tracking
- Remove all numbered migration scripts

### Files to Modify
- `DatabaseUpdater.java` - Simplify to just run schema.sql if tables don't exist
- Delete all `00XX-*.sql` files
- Create new `schema.sql` with final schema
- Update `scriptList.txt` or remove entirely

---

## Phase 2: Remove Redundant Columns from FullData

**Priority: HIGH - Reduces storage and simplifies code**

### Columns to Remove

| Column | Reason for Removal |
|--------|-------------------|
| `MinY` | Deprecated, always 0, never read |
| `DataFormatVersion` | Always V2, no other formats supported |
| `DataChecksum` | Never used for validation, only stored |

### Columns to Keep

| Column | Reason |
|--------|--------|
| `DetailLevel, PosX, PosZ` | Primary key |
| `Data` | LOD data blob |
| `ColumnWorldCompressionMode` | Per-column compression info |
| `Mapping` | Block ID mapping |
| `NorthAdjData, SouthAdjData, EastAdjData, WestAdjData` | Adjacent data for rendering |
| `CompressionMode` | Still support multiple compression algorithms |
| `ApplyToParent` | Update propagation flag |
| `IsComplete` | Completion tracking |
| `LastModifiedUnixDateTime` | Used for multiplayer sync |
| `CreatedUnixDateTime` | Metadata |

### Files to Modify
- `FullDataSourceV2DTO.java` - Remove dataFormatVersion, dataChecksum fields
- `FullDataSourceV2Repo.java` - Remove from all SQL statements
- `schema.sql` - Don't include these columns

---

## Phase 3: Remove Legacy Tables

**Priority: HIGH - Reduces database size**

### Tables to Remove
- `Legacy_FullData_V1` - Migration remnant, no longer needed

### Tables to Keep
- `FullData` - Main LOD storage
- `ChunkHash` - Chunk change detection
- `BeaconBeam` - Beacon rendering

---

## Phase 4: Simplify ChunkHash Table

**Priority: MEDIUM - Minor optimization**

### Current Columns
- ChunkPosX, ChunkPosZ (PK)
- ChunkHash
- LastModifiedUnixDateTime
- CreatedUnixDateTime

### Analysis
- Timestamps are written but never read
- Only ChunkHash is actually used for comparison

### Decision
- **Keep timestamps** - Low cost, useful for debugging/diagnostics
- Or **Remove** if we want maximum simplicity

---

## Phase 5: Query Optimization

**Priority: HIGH - Performance improvement**

### Current Issues Identified

#### 5.1 getPositionsToUpdate Query
```sql
SELECT DetailLevel, PosX, PosZ,
   abs((PosX << (6 + DetailLevel)) - ?) + abs((PosZ << (6 + DetailLevel)) - ?) AS Distance
FROM FullData
WHERE ApplyToParent = 1
ORDER BY DetailLevel ASC, Distance ASC
LIMIT ?;
```

**Problems:**
- Computed distance column prevents index usage for ordering
- Partial index only covers ApplyToParent, not position columns

**Optimization:**
- Create covering index: `CREATE INDEX idx_apply_parent_pos ON FullData(ApplyToParent, DetailLevel, PosX, PosZ) WHERE ApplyToParent = 1`
- Consider pre-computing distance ranges instead of exact distance

#### 5.2 existsAndIsComplete Query
```sql
SELECT IsComplete FROM FullData
WHERE DetailLevel = ? AND PosX = ? AND PosZ = ?;
```

**Current:** Good - uses primary key index

**Potential improvement:**
- Could use `SELECT 1 ... LIMIT 1` instead of selecting IsComplete if we only check existence
- But we need the IsComplete value, so current approach is correct

#### 5.3 UPSERT Pattern
```sql
INSERT INTO FullData (...) VALUES (...)
ON CONFLICT(DetailLevel, PosX, PosZ) DO UPDATE SET ...
```

**Current:** Good - atomic operation, no race conditions

**Already optimized** in FullDataSourceV2Repo, but base class still uses lock-based check-then-insert pattern.

#### 5.4 Timestamp Queries (Multiplayer)
```sql
SELECT LastModifiedUnixDateTime FROM FullData WHERE ...
SELECT PosX, PosZ, LastModifiedUnixDateTime FROM FullData WHERE DetailLevel = ? AND PosX BETWEEN ? AND ? AND PosZ BETWEEN ? AND ?
```

**Problem:** Range query on PosX/PosZ may not use index efficiently

**Optimization:**
- Add index: `CREATE INDEX idx_pos_range ON FullData(DetailLevel, PosX, PosZ)`
- This is already the primary key, so should be efficient

#### 5.5 Adjacent Data Queries
```sql
SELECT DataChecksum, ColumnWorldCompressionMode, Mapping, DataFormatVersion, CompressionMode, ApplyToParent,
   LastModifiedUnixDateTime, CreatedUnixDateTime, NorthAdjData as AdjData
FROM FullData WHERE ...
```

**After removing columns:** Query becomes simpler and faster

---

## Phase 6: Index Optimization

**Priority: MEDIUM - Performance tuning**

### Current Indexes
1. Primary key: `(DetailLevel, PosX, PosZ)`
2. Partial index: `ApplyToParent WHERE ApplyToParent = 1`

### Proposed Changes

#### Remove
- None needed

#### Modify
- Change ApplyToParent index to covering index:
```sql
CREATE INDEX idx_apply_parent_covering ON FullData(ApplyToParent, DetailLevel, PosX, PosZ)
WHERE ApplyToParent = 1;
```

#### Add (if needed based on profiling)
- None initially - primary key should cover most queries

---

## Phase 7: PRAGMA Optimization Review

**Priority: LOW - Already implemented**

### Current PRAGMAs (in AbstractDhRepo)
- `busy_timeout = 5000`
- `cache_size = -65536` (64MB)
- `temp_store = MEMORY`
- `mmap_size = 268435456` (256MB, if local)
- `journal_mode = WAL`
- `synchronous = NORMAL`

### Assessment
These are already well-optimized. No changes needed.

---

## Implementation Order

### Step 1: Create new schema.sql
Write the final, clean schema without any migration logic.

### Step 2: Update DatabaseUpdater
Simplify to just create tables if they don't exist, no migration tracking.

### Step 3: Remove columns from DTO and Repo
Update FullDataSourceV2DTO and FullDataSourceV2Repo to remove:
- MinY
- DataFormatVersion
- DataChecksum

### Step 4: Delete old migration scripts
Remove all 00XX-*.sql files and scriptList.txt.

### Step 5: Update index
Replace partial ApplyToParent index with covering index.

### Step 6: Test and verify
Run tests, check database creation works correctly.

---

## Final Schema (schema.sql)

```sql
-- Distant Horizons Au Naturel - Database Schema
-- Single-version schema, no migrations

PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;

-- Main LOD data storage
CREATE TABLE IF NOT EXISTS FullData (
    DetailLevel TINYINT NOT NULL,
    PosX INT NOT NULL,
    PosZ INT NOT NULL,

    Data BLOB NULL,
    ColumnWorldCompressionMode BLOB NULL,
    Mapping BLOB NULL,

    NorthAdjData BLOB NULL,
    SouthAdjData BLOB NULL,
    EastAdjData BLOB NULL,
    WestAdjData BLOB NULL,

    CompressionMode TINYINT NULL,
    ApplyToParent BIT NULL,
    IsComplete BIT NULL,

    LastModifiedUnixDateTime BIGINT NOT NULL,
    CreatedUnixDateTime BIGINT NOT NULL,

    PRIMARY KEY (DetailLevel, PosX, PosZ)
);

-- Index for update propagation queries
CREATE INDEX IF NOT EXISTS idx_apply_parent ON FullData(ApplyToParent, DetailLevel, PosX, PosZ)
WHERE ApplyToParent = 1;

-- Chunk hash tracking for change detection
CREATE TABLE IF NOT EXISTS ChunkHash (
    ChunkPosX INT NOT NULL,
    ChunkPosZ INT NOT NULL,
    ChunkHash INT NOT NULL,
    LastModifiedUnixDateTime BIGINT NOT NULL,
    CreatedUnixDateTime BIGINT NOT NULL,
    PRIMARY KEY (ChunkPosX, ChunkPosZ)
);

-- Beacon beam storage
CREATE TABLE IF NOT EXISTS BeaconBeam (
    BlockPosX INT NOT NULL,
    BlockPosY INT NOT NULL,
    BlockPosZ INT NOT NULL,
    ColorR INT NOT NULL,
    ColorG INT NOT NULL,
    ColorB INT NOT NULL,
    LastModifiedUnixDateTime BIGINT NOT NULL,
    CreatedUnixDateTime BIGINT NOT NULL,
    PRIMARY KEY (BlockPosX, BlockPosY, BlockPosZ)
);
```

---

## Estimated Impact

| Change | Storage Reduction | Query Improvement |
|--------|------------------|-------------------|
| Remove MinY | ~4 bytes/row | Negligible |
| Remove DataFormatVersion | ~1 byte/row | Negligible |
| Remove DataChecksum | ~4 bytes/row | Negligible |
| Remove Legacy table | Entire table | N/A |
| Covering index | None | ~10-20% for update queries |
| Remove migration overhead | None | Faster DB init |

**Total per-row savings:** ~9 bytes (small but compounds with millions of rows)
**Code complexity reduction:** Significant - removes entire migration system
