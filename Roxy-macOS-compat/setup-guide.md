# Roxy macOS Compatibility Patch Setup

This guide provides instructions on the installation of the Roxy macOS compatibility bundle into a fresh instance in either Prism or Modrinth on Apple Silicon. The rendering profile uses macOS 26 or later, Minecraft 1.21.1, Roxy 0.4.1+, as well as the usual Roxy mod dependencies. The ZIP supplies the graphics libraries and launcher wrapper. Install Minecraft, Java and NeoForge through your desired launcher, then add the matching mod jars.

The tested machine was an M1 Pro MacBook Pro. Other Apple Silicon Macs and later macOS versions have not been verified. The runtime requires native ARM64 Java 21 and macOS 26 or later. It is an experimental Zink runtime that advertises OpenGL 4.6 to Minecraft; this version override does not establish full OpenGL 4.6 conformance. Iris and shader packs have not been verified.

## Installation

Install Prism Launcher using its [official macOS download](https://prismlauncher.org/download/macos/), or Modrinth from the [official website](https://modrinth.com/app).

Download and extract `Roxy-macOS-compat.zip` to a location that will stay in place, such as `~/Applications/Roxy-macOS-compat`. Keep the extracted folder together. It should contain `bin/roxy-macos-launch.sh`, `runtime/`, `natives/`, and `probe/`.

The package includes the prepared Apple Silicon graphics runtime, native libraries, and a graphics probe. Players do not need to build Mesa or GLFW, install the Vulkan SDK, or install Xcode command line tools. The package is ad-hoc signed and is not notarized.

### Create a clean Prism instance

In Prism Launcher, choose **Add Instance**, select Minecraft **1.21.1**, and create a new instance. In the instance's **Edit** window, install NeoForge. Keep this instance separate from existing worlds and mod setups while checking the runtime.

Add these mod files to the instance's `mods` folder, using Prism's mod downloader when it offers the required file:

- Sodium **0.8.12** for Minecraft 1.21.1 and NeoForge.
- A Roxy version greater than **0.4.1** is required, as previous jars do not have the backend changes for macOS support.
- The original Voxy **0.2.16-beta+1.21.11** jar. This is the original Voxy artifact used by the tested 1.21.1 bridge. Its filename refers to 1.21.11, so if Prism's downloader filters it out, download that exact artifact separately and place it in this instance's `mods` folder.

### Select Java and set the wrapper

In the fresh instance's **Edit** window, open **Settings** and enable the Java override. Select a native ARM64 Java 21 executable. Prism's Java test can check the selected runtime. The wrapper checks Java's reported version and architecture before starting the graphics probe.

Still in **Settings**, open **Custom Commands** and enable the instance's command overrides. Put the following in **Wrapper command**, replacing the example folder with the actual absolute path where the runtime was extracted:

```text
/bin/bash "/.../path/.../Roxy-macOS-compat/bin/roxy-macos-launch.sh"
```

Prism passes the selected Java executable and the original Minecraft arguments to the wrapper. The wrapper uses Prism's `INST_MC_DIR` environment variable to find this instance's `mods` folder, runs its graphics probe, and then starts Minecraft with the bundled graphics libraries. Do not add Java options or the Minecraft command to the wrapper field.

## Launch information and logs

Launch the fresh instance and wait for Prism's console to report that the graphics probe passed before Minecraft opens. If the probe fails, Minecraft will not start; read the reason shown in the console and the report at `<Minecraft folder>/roxy-macos-reports/graphics-probe.log`. Shader compilation details are saved under `<Minecraft folder>/roxy-macos-reports/voxy-shaders/`. Prism's **Minecraft Folder** button opens the directory containing these reports and the `mods` folder.

If Minecraft fails to start, first confirm the instance uses ARM64 Java 21 and that its `mods` folder contains the required files. For a normal Apple OpenGL launch without the experimental runtime, disable or clear the Prism wrapper for that instance. A Mac-enabled Roxy build disables Voxy rendering on stock Apple OpenGL while allowing Minecraft and Sodium to continue.

### Modrinth setup

The same runtime wrapper can be used with Modrinth App. Create a separate Minecraft **1.21.1** instance with NeoForge **21.1.241**, then add the same three mod jars listed above. In that instance's settings, select native ARM64 Java 21 in **Java and memory**. Open **Hooks** and set **Wrapper** to the same `/bin/bash "/.../path/.../bin/roxy-macos-launch.sh"` command used for Prism.

Apply the hook to this instance. The wrapper supplies the environment variables and Java options; no system-wide graphics configuration is required. Launch the instance and use the same graphics-log and in-world checks. Modrinth's instance folder is the Minecraft folder, so `roxy-macos-reports` appears directly inside it.

## Removing the runtime

To stop using the experimental runtime, clear the instance's **Wrapper command** or turn off its command overrides. The portable runtime folder can then be removed after Prism is closed. Reports remain in the instance's `roxy-macos-reports` folder until you remove them.

For Prism's wrapper and instance command settings, see the [Prism Launcher Custom Commands guide](https://prismlauncher.org/wiki/help-pages/custom-commands/). For Java selection, see [Prism Launcher Java Settings](https://prismlauncher.org/wiki/help-pages/java-settings/).
