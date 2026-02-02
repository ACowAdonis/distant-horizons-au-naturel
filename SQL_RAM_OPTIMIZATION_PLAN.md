# SQL & RAM Optimization Plan

This document outlines prioritized optimization opportunities identified during the SQL and RAM audit.

---

## Priority Tiers

### Tier 1: Quick Wins (Low Risk, Low Effort, Measurable Impact)

These can be implemented in under an hour with minimal risk of regression.

| ID | Change | Location | Effort | Risk | Impact |
|----|--------|----------|--------|------|--------|
| 1.1 | Set query timeout to 30s | `AbstractDhRepo.java:52` | 5 min | Very Low | Prevents hung queries |
| 1.2 | Add IsComplete index | `schema.sql` | 5 min | Very Low | Faster completion checks |
| 1.3 | Add timestamp range index | `schema.sql` | 5 min | Very Low | Faster multiplayer sync |
| 1.4 | Cache API listener check | `LodDataBuilder.java:128,237` | 15 min | Very Low | Minor CPU per chunk |

#### 1.1 Set Query Timeout
```java
// AbstractDhRepo.java:52
public static final int TIMEOUT_SECONDS = 30;  // was 0 (infinite)
```
- **Why**: Queries can hang indefinitely on database locks or corruption
- **Risk**: Only affects genuinely stuck queries

#### 1.2 Add IsComplete Index
```sql
-- schema.sql
CREATE INDEX IF NOT EXISTS idx_detail_complete 
ON FullData(DetailLevel, PosX, PosZ, IsComplete);
```
- **Why**: `existsAndIsComplete()` is called during generation prioritization
- **Risk**: Additive index, SQLite handles gracefully

#### 1.3 Add Timestamp Range Index
```sql
-- schema.sql  
CREATE INDEX IF NOT EXISTS idx_timestamp_range 
ON FullData(DetailLevel, PosX, PosZ, LastModifiedUnixDateTime);
```
- **Why**: `getTimestampsForRange()` used in multiplayer sync
- **Risk**: Additive index

#### 1.4 Cache API Listener Check
```java
// LodDataBuilder.java - before chunk processing loop
boolean hasApiListeners = ApiEventInjector.INSTANCE.hasListeners(DhApiChunkProcessingEvent.class);
// Pass as parameter instead of checking 16K+ times per chunk
```

---

### Tier 2: Medium Effort, Good Impact (Low-Medium Risk)

These require more code changes but have clear benefits.

| ID | Change | Location | Effort | Risk | Impact |
|----|--------|----------|--------|------|--------|
| 2.1 | Batch position queries | `FullDataSourceV2Repo.java` | 2-4 hrs | Low | High - 20+ queries → 1 |
| 2.2 | UPSERT for ChunkHash/Beacon | `ChunkHashRepo.java`, `BeaconBeamRepo.java` | 1-2 hrs | Low | Eliminates lock contention |
| 2.3 | Combined adjacent query | `FullDataSourceV2Repo.java` | 2-3 hrs | Medium | 4 queries → 1 |

#### 2.1 Batch Position Queries
```java
// Add to FullDataSourceV2Repo.java
public Map<Long, FullDataSourceV2DTO> getByKeyBatch(Collection<Long> positions) throws SQLException {
    if (positions.isEmpty()) return Collections.emptyMap();
    
    // Build: SELECT * FROM FullData WHERE (DetailLevel, PosX, PosZ) IN (...)
    StringBuilder sql = new StringBuilder("SELECT * FROM " + getTableName() + " WHERE ");
    
    List<String> conditions = new ArrayList<>();
    for (Long pos : positions) {
        int detailLevel = DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
        conditions.add("(DetailLevel=" + detailLevel + 
                      " AND PosX=" + DhSectionPos.getX(pos) + 
                      " AND PosZ=" + DhSectionPos.getZ(pos) + ")");
    }
    sql.append(String.join(" OR ", conditions));
    
    Map<Long, FullDataSourceV2DTO> results = new HashMap<>();
    try (PreparedStatement stmt = createPreparedStatement(sql.toString());
         ResultSet rs = query(stmt)) {
        while (rs != null && rs.next()) {
            FullDataSourceV2DTO dto = convertResultSetToDto(rs);
            results.put(dto.pos, dto);
        }
    }
    return results;
}
```
- **Why**: Loading view frustum currently requires 20+ separate queries
- **Impact**: Significant reduction in database round-trips

#### 2.2 UPSERT for Other Repos
Apply same atomic UPSERT pattern from `FullDataSourceV2Repo` to:
- `ChunkHashRepo.java`
- `BeaconBeamRepo.java`

This eliminates the check-then-insert/update pattern and associated locking.

#### 2.3 Combined Adjacent Query
```java
// Replace 4 separate getAdjByPosAndDirection() calls with:
public Map<EDhDirection, FullDataSourceV2DTO> getAllAdjacentData(long pos) {
    String sql = 
        "SELECT 'NORTH' as Dir, NorthAdjData as AdjData, ... FROM FullData WHERE ... " +
        "UNION ALL " +
        "SELECT 'SOUTH' as Dir, SouthAdjData as AdjData, ... FROM FullData WHERE ...";
    // etc.
}
```
- **Risk**: ResultSet handling becomes more complex
- **Benefit**: Single round-trip for all 4 adjacent directions

---

### Tier 3: Higher Effort, Significant Impact (Medium Risk)

These require substantial changes and thorough testing.

| ID | Change | Location | Effort | Risk | Impact |
|----|--------|----------|--------|------|--------|
| 3.1 | Single-pass adjacent serialization | `FullDataSourceV2DTO.java` | 4-6 hrs | Medium | High - 5 passes → 1 |
| 3.2 | Block/Biome ID registry | `FullDataPointIdMap.java` | 6-8 hrs | Medium | High - smaller maps |
| 3.3 | Read-side LOD cache | New class | 4-6 hrs | Medium | Avoids repeated DB reads |

#### 3.1 Single-Pass Adjacent Serialization
**Current** (5 passes):
```java
writeDataSourceDataArrayToBlobV2(dataPoints, dto.compressedDataByteArray, null, ...);
writeDataSourceDataArrayToBlobV2(dataPoints, dto.compressedNorthAdjDataByteArray, NORTH, ...);
writeDataSourceDataArrayToBlobV2(dataPoints, dto.compressedSouthAdjDataByteArray, SOUTH, ...);
writeDataSourceDataArrayToBlobV2(dataPoints, dto.compressedEastAdjDataByteArray, EAST, ...);
writeDataSourceDataArrayToBlobV2(dataPoints, dto.compressedWestAdjDataByteArray, WEST, ...);
```

**Proposed** (1 pass):
```java
writeAllDataArraysToBlobs(dataPoints, dto, compressionModeEnum);
// Internal: single iteration, writes to 5 output streams simultaneously
```

- **Risk**: Changes serialization logic, needs DataSourceRepoTests verification
- **Impact**: Reduces CPU and memory churn during saves

#### 3.2 Block/Biome ID Registry
**Current**: Stringifies every block and biome name for mapping
```java
// TODO in FullDataPointIdMap.java:43
// "serializing might be really big since it stringifies every block and biome name"
```

**Proposed**: Use Minecraft's internal registry IDs
```java
// Instead of "minecraft:stone" -> store registry numeric ID
// Serialize ID mapping table separately (much smaller)
```

- **Risk**: Changes serialization format (acceptable since no backwards compat)
- **Impact**: Significant reduction in map size and memory

#### 3.3 Read-Side LOD Cache
```java
public class LoadedLodCache {
    private final Cache<Long, SoftReference<FullDataSourceV2>> cache;
    private static final int MAX_ENTRIES = 256;
    private static final long TTL_MS = 30_000; // 30 seconds
    
    public FullDataSourceV2 getOrLoad(long pos, Supplier<FullDataSourceV2> loader) {
        SoftReference<FullDataSourceV2> ref = cache.getIfPresent(pos);
        if (ref != null) {
            FullDataSourceV2 data = ref.get();
            if (data != null) return data;
        }
        FullDataSourceV2 data = loader.get();
        cache.put(pos, new SoftReference<>(data));
        return data;
    }
    
    public void invalidate(long pos) {
        cache.invalidate(pos);
    }
}
```

- **Risk**: Cache invalidation complexity (must invalidate on LOD update)
- **Impact**: Avoids repeated database reads for same LOD

---

### Tier 4: Larger Efforts (Higher Risk, Variable Impact)

These need careful consideration and may not be worth the complexity.

| ID | Change | Effort | Risk | Impact | Recommendation |
|----|--------|--------|------|--------|----------------|
| 4.1 | Predictive LOD prefetching | 8-12 hrs | Medium-High | Variable | Defer - complex, uncertain benefit |
| 4.2 | Streaming decompression | 6-10 hrs | Medium-High | Medium | Defer - conflicts with pooling |
| 4.3 | Connection pooling changes | 4-6 hrs | Medium | Low | Skip - current approach fine for SQLite |

---

### Tier 5: Monitoring (No Code Changes Yet)

| ID | Action | Purpose |
|----|--------|---------|
| 5.1 | Profile query performance | Identify actual slow queries with data |
| 5.2 | Monitor RenderBoxArrayCache evictions | Tune cache size if needed |
| 5.3 | Memory profiling under load | Find actual hotspots vs theoretical |

---

## RAM-Specific Opportunities

### Already Well-Optimized
- **PhantomArrayListPool**: Excellent GC-aware pooling with two-tier cleanup
- **FastUtil collections**: No boxing overhead for primitives
- **Database PRAGMAs**: 64MB page cache, 256MB mmap

### Potential Improvements

| ID | Change | Current | Impact | Notes |
|----|--------|---------|--------|-------|
| R1 | Bound DhApiTerrainDataCache | Unbounded (SoftRef only) | Medium | Add explicit size limit |
| R2 | Lazy-load adjacent data | Always loads all 4 | Low-Medium | Only load when needed |
| R3 | Reduce mapping stringification | Full block/biome names | High | See 3.2 above |

---

## Implementation Phases

### Phase 1: Quick Wins (Day 1)
1. [ ] Set query timeout (1.1)
2. [ ] Add IsComplete index (1.2)  
3. [ ] Add timestamp range index (1.3)
4. [ ] Cache API listener check (1.4)
5. [ ] Build and test

### Phase 2: Query Batching (Days 2-3)
1. [ ] Implement batch position queries (2.1)
2. [ ] Apply UPSERT to ChunkHash/Beacon repos (2.2)
3. [ ] Build and test

### Phase 3: Serialization (Days 4-5)
1. [ ] Single-pass adjacent serialization (3.1)
2. [ ] Block/Biome ID registry (3.2)
3. [ ] Build and test thoroughly

### Phase 4: Caching (Days 6-7)
1. [ ] Read-side LOD cache (3.3)
2. [ ] Combined adjacent query (2.3)
3. [ ] Build and test

---

## Success Metrics

| Metric | How to Measure | Target |
|--------|----------------|--------|
| Query latency | Add timing logs | < 10ms average |
| Generation throughput | LODs/second counter | Maintain or improve |
| Memory usage | F3 debug menu, VisualVM | No regression |
| Load time after teleport | Manual timing | Perceivable improvement |

---

## Risk Mitigation

1. **All changes**: Run `./gradlew build` (includes tests)
2. **Schema changes**: Test fresh DB AND existing DB
3. **Serialization changes**: Verify round-trip in DataSourceRepoTests
4. **Cache changes**: Test under memory pressure (limit JVM heap)

---

## Not Recommended

| Change | Reason |
|--------|--------|
| R-tree for BeaconBeams | Overkill unless beam count grows significantly |
| Aggressive compression | CPU cost outweighs disk savings |
| Custom connection pooling | Current approach already efficient for SQLite |
