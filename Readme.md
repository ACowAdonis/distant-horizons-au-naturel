# Distant Horizons - Au Naturel

A specialized fork of [Distant Horizons](https://gitlab.com/jeseibel/distant-horizons) optimized for **Forge 1.20.1**.

Developed for the **Au Naturel Modpack**.

## About This Fork

This fork removes multi-loader and multi-version support to focus exclusively on Forge 1.20.1, resulting in a simplified and optimized codebase for specific modpack use.

### Key Changes from Upstream

**Removed Features:**
- Fabric and NeoForge loader support
- Multi-version support (1.16.x - 1.21.x)
- V1 data format support
- SURFACE and FEATURES generation modes

**Improvements:**
- Fixed LAN multiplayer - clients now properly generate/display LODs
- Fixed upload task counter leak causing progressive LOD loading slowdown
- Performance optimizations (cloud culling cache, adaptive transfer tuning)
- Improved LAN multiplayer defaults for faster LOD transfer

### Supported Version

- **Minecraft:** 1.20.1
- **Mod Loader:** Forge 47.2.1
- **Java:** 25 (targeted), 17+ (minimum)

## Building

```bash
./gradlew build
```

The compiled jar will be in `forge/build/libs/`.

## License

This project is licensed under the **GNU Lesser General Public License v3.0** (LGPL-3.0).

See [LICENSE.LESSER.txt](LICENSE.LESSER.txt) and [LICENSE.txt](LICENSE.txt) for details.

## Credits

**Original Project:** [Distant Horizons](https://gitlab.com/jeseibel/distant-horizons) by James Seibel and contributors

This fork is based on Distant Horizons and inherits its LGPL v3 license. All original copyright notices and license headers have been preserved.

## Open Source Acknowledgements

From the original project:

- [Forgix](https://github.com/PacifistMC/Forgix) - Jar merging
- [LZ4 for Java](https://github.com/lz4/lz4-java) - Data compression
- [NightConfig](https://github.com/TheElectronWill/night-config) - Config handling
- [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) - Database
