# Distant Horizons Optimization Plan
## Target: Forge 1.20.1 Only Build

This plan removes unused components in phases. Each phase is independently testable and can be committed separately.

---

## Phase 1: Remove Fabric Loader

**Goal:** Eliminate Fabric platform support entirely.

### 1.1 Files to Delete

```
/fabric/                           # Entire directory
```

### 1.2 Files to Modify

**settings.gradle** - Remove Fabric from included projects:
- Remove `include 'fabric'` line
- Remove any Fabric-specific configuration blocks

**build.gradle** (root) - Remove Fabric build targets:
- Remove Fabric from `subprojects` configurations
- Remove Fabric from any combined JAR tasks (Forgix)
- Remove Fabric dependencies

**gradle.properties** - Remove Fabric-specific properties if present

### 1.3 Verification Steps

```bash
# 1. Build Forge-only
./gradlew :forge:build -PmcVer=1.20.1

# 2. Verify JAR exists
ls -la forge/build/libs/

# 3. Test in Minecraft
# - Install JAR in Forge 1.20.1 instance
# - Launch game, verify mod loads
# - Enter single-player world, verify LODs generate
# - Verify no errors in logs
```

### 1.4 Expected Outcome
- Build succeeds with only Forge output
- JAR size reduced ~15-20%
- No Fabric artifacts produced

---

## Phase 2: Remove NeoForge Loader

**Goal:** Eliminate NeoForge platform support entirely.

### 2.1 Files to Delete

```
/neoforge/                         # Entire directory
```

### 2.2 Files to Modify

**settings.gradle** - Remove NeoForge from included projects:
- Remove `include 'neoforge'` line

**build.gradle** (root) - Remove NeoForge build targets:
- Remove NeoForge from `subprojects` configurations
- Remove NeoForge from combined JAR tasks
- Remove NeoForge dependencies

### 2.3 Verification Steps

```bash
# 1. Build Forge-only
./gradlew :forge:build -PmcVer=1.20.1

# 2. Verify only Forge JAR exists
ls -la forge/build/libs/
ls -la build/libs/  # Should have no NeoForge JARs

# 3. Test in Minecraft (same as Phase 1)
```

### 2.4 Expected Outcome
- Build produces only Forge JAR
- Cumulative JAR size reduction ~30-35% from original
- Build time significantly reduced

---

## Phase 3: Remove Multi-Version Support

**Goal:** Target only Minecraft 1.20.1, remove version abstraction overhead.

### 3.1 Files to Delete

```
/versionProperties/1.16.5.properties
/versionProperties/1.17.1.properties
/versionProperties/1.18.2.properties
/versionProperties/1.19.2.properties
/versionProperties/1.19.4.properties
/versionProperties/1.20.2.properties
/versionProperties/1.20.4.properties
/versionProperties/1.20.6.properties
/versionProperties/1.21.properties
/versionProperties/1.21.1.properties
/versionProperties/1.21.3.properties
/versionProperties/1.21.4.properties
# Keep only: 1.20.1.properties
```

### 3.2 Files to Modify

**build.gradle** (root):
- Hardcode `mcVer = '1.20.1'` instead of property lookup
- Remove version-conditional logic
- Simplify dependency version resolution

**gradle.properties**:
- Set `mcVer=1.20.1` as fixed value
- Remove version-switching logic

### 3.3 Files to Audit

Search for version-conditional code patterns:
```bash
# Find version checks in Java code
grep -r "mcVersion" --include="*.java" .
grep -r "1\.20" --include="*.java" .
grep -r "MinecraftVersion" --include="*.java" .
```

These may contain dead branches that can be simplified.

### 3.4 Verification Steps

```bash
# 1. Build without version parameter (should default to 1.20.1)
./gradlew :forge:build

# 2. Verify correct MC version in output
unzip -p forge/build/libs/*.jar fabric.mod.json 2>/dev/null || \
unzip -p forge/build/libs/*.jar META-INF/mods.toml | grep -i minecraft

# 3. Test in Minecraft 1.20.1
# - Verify mod loads correctly
# - Check version string in mod menu
```

### 3.5 Expected Outcome
- Build simplified to single version
- No version-switching overhead
- Slightly smaller JAR (removed compatibility shims)

---

## Phase 4: Remove V1 Data Format Support

**Goal:** Eliminate legacy data format, support only V2.

**Constraint:** Only apply if all target worlds are new (created with V2 format).

### 4.1 Files to Delete

```
coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/fullData/sources/FullDataSourceV1.java
coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/file/fullDatafile/FullDataSourceV1Repo.java
coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/fullData/sources/FullDataSourceV1DTO.java
```

### 4.2 Files to Modify

Search for V1 references:
```bash
grep -r "V1" --include="*.java" coreSubProjects/core/src/
grep -r "FullDataSourceV1" --include="*.java" .
```

Likely modifications needed in:
- Data source loading/detection code (remove V1 branch)
- Migration utilities (remove V1→V2 conversion)
- Repository factory/selection logic

### 4.3 Verification Steps

```bash
# 1. Build
./gradlew :forge:build

# 2. Test with NEW world
# - Create new single-player world
# - Explore to generate LODs
# - Exit and rejoin, verify LODs persist
# - Check database format is V2

# 3. Verify V1 world rejection (optional safety)
# - Attempt to load a V1 format world
# - Should fail gracefully with clear error (not crash)
```

### 4.4 Expected Outcome
- Simplified data loading path
- Slightly smaller JAR
- No V1 parsing overhead at runtime

---

## Phase 5: Remove SURFACE and FEATURES Generation Modes

**Goal:** Retain only PRE_EXISTING_ONLY and INTERNAL_SERVER generation modes.

### 5.1 Files to Delete

```
common/src/main/java/com/seibel/distanthorizons/common/wrappers/worldGeneration/step/StepSurface.java
common/src/main/java/com/seibel/distanthorizons/common/wrappers/worldGeneration/step/StepFeatures.java
```

### 5.2 Files to Modify

**EDhApiDistantGeneratorMode.java** (API enum):
```java
// Remove these entries:
// SURFACE(...)
// FEATURES(...)
```
Location: `coreSubProjects/api/src/main/java/com/seibel/distanthorizons/api/enums/worldGeneration/`

**BatchGenerator.java** (switch statement):
```java
// Remove cases for SURFACE and FEATURES
// Keep only PRE_EXISTING_ONLY and INTERNAL_SERVER
```
Location: `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/generation/`

**BatchGenerationEnvironment.java**:
- Remove `StepSurface` instantiation
- Remove `StepFeatures` instantiation
- Remove step execution calls in `generateDirect()`
Location: `common/src/main/java/com/seibel/distanthorizons/common/wrappers/worldGeneration/`

**Config.java**:
- Update `distantGeneratorMode` config entry
- Change default if currently SURFACE or FEATURES
- Update allowed values list
Location: `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/config/`

### 5.3 Config UI Updates

Search for UI references:
```bash
grep -r "SURFACE\|FEATURES" --include="*.java" coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/config/
```

Ensure config screens don't offer removed options.

### 5.4 Verification Steps

```bash
# 1. Build
./gradlew :forge:build

# 2. Verify enum only has valid values
grep -A5 "enum EDhApiDistantGeneratorMode" coreSubProjects/api/src/main/java/com/seibel/distanthorizons/api/enums/worldGeneration/EDhApiDistantGeneratorMode.java

# 3. Test PRE_EXISTING_ONLY mode
# - Set config to PRE_EXISTING_ONLY
# - Load world with existing chunks
# - Verify LODs generate from loaded chunks only

# 4. Test INTERNAL_SERVER mode
# - Set config to INTERNAL_SERVER
# - Load world, move to unexplored area
# - Verify LODs generate via server worldgen

# 5. Verify config UI
# - Open DH config screen in-game
# - Check generation mode dropdown
# - Should only show PRE_EXISTING_ONLY and INTERNAL_SERVER
```

### 5.5 Expected Outcome
- Simplified generation mode logic
- Cleaner configuration options
- Removed dead code paths

---

## Phase 6: Final Cleanup and Optimization

**Goal:** Remove any orphaned code, optimize remaining systems.

### 6.1 Tasks

1. **Dead code analysis**
   ```bash
   # Find unused imports
   ./gradlew checkstyleMain  # If configured

   # Find unreferenced classes (manual review)
   ```

2. **Dependency audit**
   - Review `build.gradle` dependencies
   - Remove any libraries only used by deleted code

3. **Mixin cleanup**
   - Review `DistantHorizons.forge.mixins.json`
   - Remove mixins for deleted functionality

4. **Resource cleanup**
   - Remove unused assets, lang files for other platforms

### 6.2 Verification Steps

```bash
# 1. Full clean build
./gradlew clean :forge:build

# 2. Check JAR contents
jar tf forge/build/libs/*.jar | grep -E "\.(class|json)$" | wc -l

# 3. Comprehensive gameplay test
# - Single-player: new world, explore, verify LODs
# - Single-player: rejoin, verify persistence
# - Multiplayer: connect to DH-enabled server
# - Multiplayer: verify LOD streaming from server
# - Multiplayer: verify local LOD generation from chunks
```

---

## Verification Checklist

Use this checklist after each phase:

### Build Verification
- [ ] `./gradlew clean :forge:build` succeeds
- [ ] No compilation errors
- [ ] No warnings related to removed code
- [ ] JAR file produced in expected location

### Runtime Verification
- [ ] Mod loads without errors
- [ ] No exceptions in log on startup
- [ ] Config screen accessible and correct
- [ ] Generation modes work as expected

### Functional Verification
- [ ] Single-player LOD generation works
- [ ] LOD persistence across sessions works
- [ ] Multiplayer server connection works
- [ ] Server→client LOD streaming works
- [ ] Client chunk→LOD conversion works

---

## Rollback Strategy

Each phase should be committed separately. If issues arise:

```bash
# View phase commits
git log --oneline

# Revert specific phase
git revert <commit-hash>

# Or reset to before phase
git reset --hard <commit-before-phase>
```

---

## Estimated Timeline

| Phase | Complexity | Dependencies |
|-------|------------|--------------|
| 1. Remove Fabric | Low | None |
| 2. Remove NeoForge | Low | Phase 1 |
| 3. Remove Multi-Version | Low-Medium | Phase 2 |
| 4. Remove V1 Format | Low | None (independent) |
| 5. Remove SURFACE/FEATURES | Medium | None (independent) |
| 6. Final Cleanup | Low | All above |

Phases 4 and 5 can be done in parallel with Phases 1-3.

---

## Notes

- Always create a backup/branch before starting each phase
- Test multiplayer functionality after each phase
- Monitor memory usage before/after major changes
- Keep detailed notes of any unexpected issues for future reference
