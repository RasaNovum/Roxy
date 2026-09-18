<h1 align="center">Roxy<br>
<a href="https://www.curseforge.com/minecraft/mc-mods/roxy"><img src="https://img.shields.io/badge/CurseForge-1.21.1-orange?style=for-the-badge"></a>
<a href="https://modrinth.com/mod/roxy"><img src="https://img.shields.io/badge/Modrinth-1.21.1-green?style=for-the-badge"></a>
<a href="https://twitter.com/Rasa_Novum"><img src="https://img.shields.io/badge/Xitter-black?style=for-the-badge"></a>
<a href="https://discord.gg/TxpknJHMBT"><img src="https://img.shields.io/badge/Discord-5865F2?style=for-the-badge"></a>
<a href=""><img src="https://img.shields.io/badge/Made_In_Canada-d52b1e?style=for-the-badge"></a>
</h1>

**Roxy** allows for the loading of **Voxy** on NeoForge.

Voxy is a Fabric exclusive LOD rendering mod. Roxy provides a translation layer between Voxy and NeoForge, allowing an original Voxy jar to function not only on NeoForge, but also on a version that Voxy did not release for.

You **require** an original [Voxy .jar](https://modrinth.com/mod/voxy/version/H3w2nVdU) for this mod to function, Roxy **does not** function without it. Currently, `voxy-0.2.16-beta` for MC 1.21.11 is the only verified version that works entirely as intended.

## Requirements

- Minecraft 1.21.1 (Neoforge)
- Forgified Fabric API
- Sodium 0.8.12+
- Voxy 0.2.16-beta

## Compatibility

Roxy is compatible with the following usual suspects:

- Create
- Sable
- Iris
- Voxy Worldgen v2
- LOD Server Support
- Chunksmith
- and many more!

Roxy *should* also be compatible with Sinytra Connector and Connector-loaded Fabric mods as of v0.1.6+.

Many major incompatibilites that are present with Voxy natively on Fabric will also remain so on NeoForge when using Roxy. Please be aware of this when reporting issues.

AMD GPUs using Mesa on Linux may show terrain-shaped gaps in shader-rendered water because of Mesa's Hyper-Z depth compression. Add `AMD_DEBUG=nohyperz` to the launcher's instance environment variables and restart the game.

Please report any major incompatibilities or bugs, along with logs, on the [Github](https://github.com/RasaNovum/Roxy/issues).

## License

MIT. This does not relicense Voxy; Voxy remains All Rights Reserved and is not redistributed.
