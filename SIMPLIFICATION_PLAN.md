# Distant Horizons Au Naturel - Simplification Plan

## Guiding Principles

This fork assumes:
1. **All chunks are pre-existing OR fully generated** - no partial generation support
2. **Single Minecraft version (1.20.1)** - no multi-version support
3. **No backwards compatibility requirements** - can break old saves/configs
4. **Modern OpenGL (4.3+)** - can remove legacy GL paths

---

## Phase 1: Low Risk, High Impact

### 1.1 Remove Auto-Updater Infrastructure
**Risk: Low | Complexity: Low | Benefit: Medium**

The auto-updater is unnecessary for a fork and adds complexity.

**Files to remove:**
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/jar/updater/SelfUpdater.java`
- `common/src/main/java/com/seibel/distanthorizons/common/wrappers/gui/updater/UpdateModScreen.java`
- `common/src/main/java/com/seibel/distanthorizons/common/wrappers/gui/updater/ChangelogScreen.java`
- `coreSubProjects/api/src/main/java/com/seibel/distanthorizons/api/enums/config/EDhApiUpdateBranch.java`

**Files to modify:**
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/config/Config.java` - Remove `AutoUpdater` config section (lines 873-900+)
- `common/src/main/java/com/seibel/distanthorizons/common/wrappers/gui/ClassicConfigGUI.java` - Remove updater button/references
- `common/src/main/java/com/seibel/distanthorizons/common/AbstractModInitializer.java` - Remove updater initialization
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/jar/installer/ModrinthGetter.java` - Review if still needed

---

### 1.2 Simplify Generation Steps
**Risk: Low | Complexity: Medium | Benefit: High**

Current: 7 enum values (DOWN_SAMPLED, EMPTY, STRUCTURE_START, STRUCTURE_REFERENCE, BIOMES, NOISE, LIGHT)
Proposed: 2 values (EMPTY, COMPLETE)

Intermediate steps are never used - chunks are always fully generated.

**Files to modify:**
- `coreSubProjects/api/src/main/java/com/seibel/distanthorizons/api/enums/worldGeneration/EDhApiWorldGenerationStep.java`
  - Remove: STRUCTURE_START, STRUCTURE_REFERENCE, BIOMES, NOISE
  - Rename: LIGHT -> COMPLETE
  - Consider: Merge DOWN_SAMPLED into EMPTY (both mean "needs real data")

- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/fullData/sources/FullDataSourceV2.java`
  - Simplify merge logic that checks intermediate steps
  - Simplify `determineMinWorldGenStepForTwoByTwoColumn()` and related methods

- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/file/fullDatafile/GeneratedFullDataSourceProvider.java`
  - Simplify `isFullyGenerated()` checks
  - Simplify `getPositionsToRetrieve()` logic

- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/generation/LodVerificationService.java`
  - Simplify `isPositionIncomplete()` to binary check

---

### 1.3 Remove V1 Data Format Support
**Risk: Low | Complexity: Low | Benefit: Medium**

V1 data auto-converts to V2 on read. With no backwards compatibility requirement, remove V1 entirely.

**Files to modify:**
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/sql/dto/FullDataSourceV2DTO.java`
  - Remove `DATA_FORMAT.V1_NO_ADJACENT_DATA` constant
  - Remove `readBlobToDataSourceDataArrayV1()` method (~100 lines)
  - Remove `writeDataSourceDataArrayToBlobV1()` method (~25 lines)
  - Remove V1 conditional in `populateDataSource()`

- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/file/fullDatafile/V2/FullDataSourceProviderV2.java`
  - Remove V1 format detection and conversion logic

---

### 1.4 Remove Deprecated Z_STD_STREAM Compression (Write Path)
**Risk: Low | Complexity: Low | Benefit: Low**

Keep read support temporarily for migration, remove from write path.

**Files to modify:**
- `coreSubProjects/api/src/main/java/com/seibel/distanthorizons/api/enums/config/EDhApiDataCompressionMode.java`
  - Mark Z_STD_STREAM as read-only/deprecated more aggressively

---

## Phase 2: Medium Risk, High Impact

### 2.1 SQL Layer Simplification
**Risk: Medium | Complexity: High | Benefit: High**

**Changes:**
1. Remove generic `AbstractDhRepo<TKey, TDTO>` abstractions - only 3 concrete implementations exist
2. Use atomic UPSERT pattern in all repos (currently only FullDataSourceV2Repo has it)
3. Fix SQL injection vulnerabilities in DatabaseUpdater (use PreparedStatements)
4. Remove deprecated MinY column from schema

**Files to modify:**
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/sql/AbstractDhRepo.java`
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/sql/DatabaseUpdater.java`
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/sql/repo/FullDataSourceV2Repo.java`
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/sql/repo/BeaconBeamRepo.java`
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/sql/repo/ChunkHashRepo.java`

**Optional (higher effort):**
- Replace custom connection management with HikariCP

---

### 2.2 Thread Pool Consolidation
**Risk: Medium | Complexity: Medium | Benefit: Medium**

Current: 8 separate thread pools
Proposed: 3-4 consolidated pools

| Current Pool | Proposed |
|--------------|----------|
| fileHandlerThreadPool | IO Pool |
| renderSectionLoadThreadPool | IO Pool |
| fullDataMigrationThreadPool | IO Pool |
| worldGenThreadPool | Compute Pool |
| chunkToLodBuilderThreadPool | Compute Pool |
| updatePropagatorThreadPool | Compute Pool |
| beaconCullingThreadPool | Render Pool |
| networkCompressionThreadPool | IO Pool |

**Files to modify:**
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/util/threading/ThreadPoolUtil.java`
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/util/threading/PriorityTaskPicker.java`

---

### 2.3 Remove Pre-GL43 Rendering Path
**Risk: Medium | Complexity: Medium | Benefit: Medium**

Since targeting GL 4.3+, remove the GL 3.2 fallback path.

**Files to remove:**
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/render/glObject/vertexAttribute/VertexAttributePreGL43.java` (~255 lines)

**Files to modify:**
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/render/glObject/vertexAttribute/AbstractVertexAttribute.java`
  - Remove factory conditional, always use PostGL43
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/render/glObject/GLProxy.java`
  - Remove `vertexAttributeBufferBindingSupported` checks
  - Update minimum GL requirement to 4.3

---

## Phase 3: Higher Risk, Architectural Changes

### 3.1 LodVerificationService Full Simplification
**Risk: Medium-High | Complexity: Medium | Benefit: Medium**

Building on scan parameter changes already made (256 positions/5s), further simplify:

**Remove:**
- PendingPosition class and all retry backoff logic
- Per-position failure tracking (ConcurrentHashMap)
- `onPositionFailed()` / `onPositionCompleted()` callbacks
- Retry timing constants

**Replace with:**
- Simple existence check per position
- Continuous spiral scan handles "retry" naturally
- No state tracking between scans

**Files to modify:**
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/generation/LodVerificationService.java`

---

### 3.2 Remove Version-Specific Documentation and Dead Code
**Risk: Low | Complexity: Low | Benefit: Low**

**Remove:**
- Dead preprocessor directives (`//#if MC_VER <= MC_1_XX_X`)
- `McObjectConverter.Convert()` deprecated method (JOML is 1.20.1 standard)
- API documentation referencing versions other than 1.20.1
- Unused GL 4.5 named objects flag in GLProxy
- Deprecated `onlyLoadCenterLods` config option

**Files to modify:**
- `common/src/main/java/com/seibel/distanthorizons/common/wrappers/WrapperFactory.java`
- `common/src/main/java/com/seibel/distanthorizons/common/wrappers/McObjectConverter.java`
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/render/glObject/GLProxy.java`
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/config/Config.java`
- Various API interface files (documentation updates)

---

### 3.3 Database Key Optimization (Optional)
**Risk: High | Complexity: High | Benefit: Medium**

Current: 3-field WHERE clause (DetailLevel, PosX, PosZ)
Proposed: Single 64-bit composite key (already packed in memory)

**Benefits:**
- Single index lookup instead of 3-field index
- Simpler queries
- Matches in-memory representation

**Requires:**
- Database schema migration
- All query code rewrite

---

### 3.4 columnGenerationSteps to BitSet (Optional)
**Risk: High | Complexity: High | Benefit: Low-Medium**

If generation steps are binary (EMPTY/COMPLETE), could use BitSet instead of ByteArrayList:

| Current | Proposed |
|---------|----------|
| 4096 bytes per section | 512 bytes (1 bit per column) |

**Risks:**
- Serialization format change
- All read/write paths must change

---

## Implementation Order

```
Week 1: Phase 1 (Low Risk)
  Day 1-2: 1.1 Remove auto-updater
  Day 3-4: 1.2 Simplify generation steps
  Day 5:   1.3 Remove V1 data format
           1.4 Z_STD_STREAM cleanup

Week 2-3: Phase 2 (Medium Risk)
  2.1 SQL layer simplification
  2.2 Thread pool consolidation
  2.3 Remove Pre-GL43 path

Week 4+: Phase 3 (Higher Risk, Optional)
  3.1 LodVerificationService full simplification
  3.2 Version-specific code cleanup
  3.3 Database key optimization (if desired)
  3.4 columnGenerationSteps to BitSet (if desired)
```

---

## Estimated Impact

| Category | Code Reduction | Performance Impact |
|----------|----------------|-------------------|
| Auto-updater removal | ~500 lines | Faster startup |
| Generation steps | ~300 lines | Simpler logic paths |
| V1 format removal | ~200 lines | Cleaner serialization |
| SQL simplification | ~300 lines | Potentially faster DB ops |
| Thread pool consolidation | ~200 lines | Reduced context switching |
| GL43 path removal | ~255 lines | Simpler render setup |
| **Total** | **~1,750 lines** | Measurable improvement |

---

## Quick Wins (Can Do Immediately)

1. Remove unused GL 4.5 named objects flag in GLProxy
2. Remove deprecated `onlyLoadCenterLods` config option
3. Remove deprecated `McObjectConverter.Convert()` method
4. Clean up dead preprocessor directives
5. Update API documentation to only reference 1.20.1
