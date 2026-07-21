<div align="center">

# Roxy

**Loads Voxy 0.2.16-beta on NeoForge 1.21.1**

</div>

Voxy is a Fabric exclusive LoD rendering mod. Roxy provides a translation layer between Voxy and NeoForge, allowing an original Voxy jar to function not only on NeoForge, but also on a version that Voxy did not release for.

## How it works

- Reads `fabric.mod.json` from the Voxy jar and generates a synthetic `neoforge.mods.toml`
  (name, description, authors, icon, mixin configs, version) so FML loads it.
- Extracts Voxy's bundled `META-INF/jars` (RocksDB, LWJGL zstd/lmdb, lz4, xz, jedis) as
  game libraries.
- Applies Voxy's `voxy.accesswidener` live as a class processor, plus a small supplement
  for NeoForge-only access gaps.
- Ships minimal `net.fabricmc.*` stubs so Voxy's bytecode links; `FabricLoader` delegates
  to `ModList` / `FMLEnvironment`.
- Reimplements the `/voxy` command against NeoForge's command system.
- Adds a Chunky auto-ingest mixin targeting NeoForge's `NeoForgeWorld`.

Nothing about Voxy is hardcoded. Metadata, mixins, access wideners, and bundled jars are
all read from whatever Voxy jar is present, so Voxy updates do not require Roxy changes.

## Requirements

- Minecraft 1.21.1 (NeoForge)
- Forgified Fabric API 0.116.14+2.3.0
- Sodium 0.8.12
- Voxy 0.2.16-beta (placed in the mods folder)

## Compatibility

Roxy is compatible with the following:

- Iris, running shaders (BSL, Complementary and Photon have been tested)
- Create

Roxy is also partially compatible with Colorwheel currently, granting the improved performance from Colorwheel, however the moving Create entity light sources do not work.

Please report any other major incompatibilities, along with logs, on the [Github](https://github.com/RasaNovum/Roxy/issues).

## License

MIT. This does not relicense Voxy; Voxy remains All Rights Reserved and is not redistributed.

This project was adapted from [Foxy](https://modrinth.com/mod/foxy-mod), a project that does a similar thing for NeoForge 26.1.2
