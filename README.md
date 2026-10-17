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
- Better Fog (NeoForge 1.21.1)
- and many more!

Roxy *should* also be compatible with Sinytra Connector and Connector-loaded Fabric mods as of v0.1.6+.

Better Fog's native LOD fog is supported with its NeoForge 1.21.1 builds (2.3.0 and 2.6.0). Enable **Environmental Fog** in Voxy and **Voxy Fog** in Better Fog, then configure the fog in Better Fog's settings. Roxy adapts the external Voxy renderer to Better Fog's existing fog engine, retaining its biome, weather, cave, colour, and strength settings. Roxy's Distant Fog controls remain for Fog and No Man's Land. With an Iris shader pack active, the shader pack controls LOD fog.

On Minecraft 1.21.1, **Roxy → LOD Terrain Fade** in Sodium's video settings blends loaded terrain into LOD terrain near the render-distance edge. It is enabled by default with a two-chunk transition, adjustable from one to eight chunks. Real terrain stays visible where LOD data is missing. Settings are saved in `config/roxy-blend.json`. With Iris shader packs that support Voxy, the transition uses world-anchored coverage for real and LOD terrain, including water, ice, and glass. A depth-only LOD pass preserves continuous terrain for shadow, AO, and reflection queries while current-pixel material reads retain the selected surface. Native transparency tests selected opaque LOD terrain for occlusion and yields where replacement translucent LOD geometry exists. Translucent native sections do not act as solid occluders for the opaque LOD terrain beneath them. The Iris transition has been checked during camera movement and after stopping in a copied forest world with Complementary Reimagined r5.8.1 and Complementary Unbound r5.8.1 on Iris 1.8.14-beta.1.

Many major incompatibilities that are present with Voxy natively on Fabric will also remain so on NeoForge when using Roxy. Please be aware of this when reporting issues.

AMD GPUs using Mesa on Linux may show terrain-shaped gaps in shader-rendered water because of Mesa's Hyper-Z depth compression. Add `AMD_DEBUG=nohyperz` to the launcher's instance environment variables and restart the game.

Please report any major incompatibilities or bugs, along with logs, on the [Github](https://github.com/RasaNovum/Roxy/issues).

## License

MIT. This does not relicense Voxy; Voxy remains All Rights Reserved and is not redistributed.
