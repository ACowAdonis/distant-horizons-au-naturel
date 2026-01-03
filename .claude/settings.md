# Project Settings

## About

This is **Distant Horizons - Au Naturel**, a specialized fork of Distant Horizons optimized for Forge 1.20.1.

Developed for the **Au Naturel Modpack**.

Repository: https://github.com/ACowAdonis/distant-horizons-au-naturel

## Java Configuration

Target Java version: **Java 25**

Java 21 is available at: `/home/acow/jdk-21.0.9` (for development/building)

Use this JDK for building the project:
```bash
JAVA_HOME=/home/acow/jdk-21.0.9 ./gradlew build -x test
```

## Build Notes

- Target Minecraft version: 1.20.1
- Mod Loader: Forge 47.2.1 only
- Target JDK: Java 25
- Build JDK: Java 21 (or newer)
- No submodules - coreSubProjects is flattened into main repo

## Key Directories

- `forge/` - Forge mod loader implementation
- `common/` - Shared code between loaders (now Forge-only)
- `coreSubProjects/core/` - Core DH logic
- `coreSubProjects/api/` - Public API

## Recent Changes

- Fixed LAN multiplayer client detection
- Improved adaptive transfer speed defaults for LAN
- Performance optimizations (cloud culling cache, upload task counter fix)
- Removed Fabric, NeoForge, multi-version support
