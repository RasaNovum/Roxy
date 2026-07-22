<h1 align="center">Roxy<br>
<a href="https://www.curseforge.com/minecraft/mc-mods/roxy"><img src="https://img.shields.io/badge/CurseForge-1.20.1%20%7C%201.21.1-orange"></a>
<a href="https://modrinth.com/mod/roxy"><img src="https://img.shields.io/badge/Modrinth-1.20.1%20%7C%201.21.1-green"></a>
<a href="https://twitter.com/Rasa_Novum"><img src="https://img.shields.io/badge/Socials-Xitter-black"></a>
<a href="https://discord.gg/WGh4mq6W5U"><img src="https://img.shields.io/badge/Socials-Discord-5865F2"></a>
</h1>

**Voxy** is a Fabric exclusive LoD rendering mod. **Roxy** provides a translation layer between Voxy and NeoForge, allowing an original Voxy jar to function not only on NeoForge, but also on a version that Voxy did not release for.

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

- Minecraft 1.21.1/1.21.11 (NeoForge)
- Forgified Fabric API 0.116.14+2.3.0
- Sodium 0.8.12
- Voxy 0.2.16-beta (placed in the mods folder)

## Compatibility

Roxy 1.21.1 is compatible with the following:

- Iris, running shaders (BSL, Complementary and Photon have been tested)
- Create
- Colorwheel
- Sable

Roxy 1.21.11 is not as polished as 1.21.1, it works properly with Sodium, but Iris, Create, Colorwheel, etc. may have issues.

Other versions of the compatible mods listed above have not been explicitly tested and may not work as intended. If you try something else out and it works, let us know in [Discord](https://discord.gg/WGh4mq6W5U)!

Please report any major incompatibilities or bugs, along with logs, on the [Github](https://github.com/RasaNovum/Roxy/issues).

## License

MIT. This does not relicense Voxy; Voxy remains All Rights Reserved and is not redistributed.
