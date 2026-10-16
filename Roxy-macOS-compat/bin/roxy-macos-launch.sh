#!/usr/bin/env bash
# Launcher Java wrapper for the portable experimental Apple Silicon runtime.
# The launcher calls this as: roxy-macos-launch.sh <java> <original Java arguments...>
set -euo pipefail

fail() {
    printf 'Roxy macOS launcher: %s\n' "$*" >&2
    exit 1
}

if [[ $# -lt 1 ]]; then
    fail 'The launcher must pass the Java executable as the first argument.'
fi
roxy_java="$1"
shift
roxy_original_args=("$@")

# This script is installed at <bundle>/bin/roxy-macos-launch.sh.
roxy_bundle="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
roxy_runtime="$roxy_bundle/runtime"
roxy_natives="$roxy_bundle/natives"
roxy_probe="$roxy_bundle/probe"

[[ "$(uname -s)" == Darwin ]] || fail 'requires macOS 26 or later on Apple Silicon.'
[[ "$(uname -m)" == arm64 ]] || fail 'requires native ARM64 macOS; Rosetta is unsupported.'
roxy_macos_version="$(sw_vers -productVersion 2>/dev/null || true)"
roxy_macos_major="${roxy_macos_version%%.*}"
[[ "$roxy_macos_major" =~ ^[0-9]+$ ]] && (( roxy_macos_major >= 26 )) || \
    fail "requires macOS 26 or later (found ${roxy_macos_version:-unknown})."
[[ -x "$roxy_java" ]] || fail "Java executable is missing or not executable: $roxy_java"

# Ask the exact Java the launcher selected. Never print or log the original
# arguments, which may contain authentication tokens.
roxy_java_properties="$("$roxy_java" -XshowSettings:properties -version 2>&1)" || \
    fail 'could not inspect the selected Java runtime.'
if ! printf '%s\n' "$roxy_java_properties" | awk '
    /java.specification.version = 21([.]|$)/ { java21=1 }
    /os.arch = (aarch64|arm64)$/ { arm64=1 }
    /os.name = Mac OS X$/ { macos=1 }
    END { exit !(java21 && arm64 && macos) }
'; then
    fail 'requires native ARM64 Java 21. Select an ARM64 Java 21 installation in the launcher.'
fi

# Determine the instance root without changing the caller's working directory.
roxy_game_dir="${INST_MC_DIR:-}"
if [[ -z "$roxy_game_dir" ]]; then
    for ((roxy_i=0; roxy_i<${#roxy_original_args[@]}; roxy_i++)); do
        roxy_arg="${roxy_original_args[$roxy_i]}"
        case "$roxy_arg" in
            -Duser.dir=*) roxy_game_dir="${roxy_arg#-Duser.dir=}" ;;
            --gameDir=*) roxy_game_dir="${roxy_arg#--gameDir=}" ;;
            --gameDir)
                if (( roxy_i + 1 < ${#roxy_original_args[@]} )); then
                    roxy_i=$((roxy_i + 1))
                    roxy_game_dir="${roxy_original_args[$roxy_i]}"
                fi
                ;;
        esac
        [[ -n "$roxy_game_dir" ]] && break
    done
fi
[[ -n "$roxy_game_dir" ]] || fail 'could not find the instance directory (INST_MC_DIR, -Duser.dir, or --gameDir).'
if [[ "$roxy_game_dir" != /* ]]; then
    roxy_game_dir="$PWD/$roxy_game_dir"
fi
roxy_game_dir="$(cd "$roxy_game_dir" 2>/dev/null && pwd -P)" || \
    fail "instance directory does not exist: $roxy_game_dir"
roxy_mods="$roxy_game_dir/mods"
[[ -d "$roxy_mods" ]] || fail "instance mods directory is missing: $roxy_mods"

roxy_expected_voxy_sha=7f8226cf64a4f5dc064e1075965714b718ac5f42fb28f826135a37c64e66e10b
roxy_voxy_jar=''
roxy_voxy_count=0
roxy_voxy_sha=''
shopt -s nullglob
for roxy_mod in "$roxy_mods"/*.jar; do
    # Inspect archive entries first so only Voxy candidates need a full hash.
    # The entry check catches renamed and unsupported Voxy builds as well.
    if unzip -Z1 "$roxy_mod" 2>/dev/null | awk '
        $0 == "me/cortex/voxy/client/VoxyClient.class" { found=1 }
        END { exit !found }
    '; then
        roxy_mod_sha="$(shasum -a 256 "$roxy_mod" 2>/dev/null | awk '{print $1}')"
        roxy_voxy_jar="$roxy_mod"
        roxy_voxy_count=$((roxy_voxy_count + 1))
        roxy_voxy_sha="$roxy_mod_sha"
    fi
done
shopt -u nullglob
(( roxy_voxy_count == 1 )) || {
    if (( roxy_voxy_count == 0 )); then
        fail "Voxy is missing from $roxy_mods. Install the pinned original Voxy 0.2.16-beta+1.21.11 jar (SHA-256 $roxy_expected_voxy_sha)."
    fi
    fail "found $roxy_voxy_count Voxy jars in $roxy_mods; keep exactly one."
}
[[ "$roxy_voxy_sha" == "$roxy_expected_voxy_sha" ]] || \
    fail "Voxy has an unsupported SHA-256 ($roxy_voxy_sha); this profile requires the original 0.2.16-beta+1.21.11 jar ($roxy_expected_voxy_sha)."
roxy_roxy_count=0
roxy_compatible_roxy=''
shopt -s nullglob
for roxy_mod in "$roxy_mods"/*[Rr][Oo][Xx][Yy]*.jar; do
    roxy_roxy_count=$((roxy_roxy_count + 1))
    if unzip -Z1 "$roxy_mod" 2>/dev/null | awk '
        $0 == "net/rasanovum/roxy/loader/RoxyMacOSGraphics.class" { graphics=1 }
        $0 == "net/rasanovum/roxy/loader/RoxyJomlMemory.class" { joml=1 }
        $0 == "net/rasanovum/roxy/loader/RoxyNativePlatform.class" { natives=1 }
        END { exit !(graphics && joml && natives) }
    '; then
        roxy_compatible_roxy="$roxy_mod"
    fi
done
shopt -u nullglob
(( roxy_roxy_count == 1 )) || fail "expected one Roxy mod jar in $roxy_mods; found $roxy_roxy_count."
[[ -n "$roxy_compatible_roxy" ]] || fail "Roxy in $roxy_mods is not the compatible macOS build; it must contain RoxyMacOSGraphics, RoxyJomlMemory, and RoxyNativePlatform."

# Validate every required bundled component before running either Java process.
for roxy_file in \
    "$roxy_runtime/lib/libGL.dylib" \
    "$roxy_runtime/lib/libEGL.dylib" \
    "$roxy_runtime/lib/libglfw.3.dylib" \
    "$roxy_runtime/lib/libvulkan.1.dylib" \
    "$roxy_runtime/lib/libgallium-26.1.0-devel.dylib" \
    "$roxy_runtime/share/vulkan/icd.d/kosmickrisp_icd.json" \
    "$roxy_probe/probe.jar"; do
    [[ -f "$roxy_file" ]] || fail "bundle is incomplete; missing $roxy_file"
done
[[ -d "$roxy_natives" ]] || fail "bundle natives directory is missing: $roxy_natives"
[[ -d "$roxy_probe/lib" ]] || fail "bundle probe libraries are missing: $roxy_probe/lib"
for roxy_native in \
    roxy-lwjgl-lmdb-natives-macos-arm64.jar \
    roxy-lwjgl-zstd-natives-macos-arm64.jar \
    roxy-rocksdbjni-10.2.1-natives-macos-arm64.jar; do
    [[ -f "$roxy_natives/$roxy_native" ]] || fail "bundle native payload is missing: $roxy_natives/$roxy_native"
done
shopt -s nullglob
roxy_probe_jars=("$roxy_probe/lib"/*.jar)
shopt -u nullglob
(( ${#roxy_probe_jars[@]} > 0 )) || fail "bundle LWJGL probe libraries are missing from $roxy_probe/lib"

roxy_reports="$roxy_game_dir/roxy-macos-reports"
mkdir -p "$roxy_reports"
roxy_shader_reports="$roxy_reports/voxy-shaders"
roxy_graphics_log="$roxy_reports/graphics-probe.log"
roxy_probe_classpath="$roxy_probe/probe.jar"
for roxy_jar in "${roxy_probe_jars[@]}"; do
    roxy_probe_classpath="$roxy_probe_classpath:$roxy_jar"
done

# These variables are scoped to the probe and game child processes. The
# prototype intentionally advertises 4.6/GLSL 460 and is experimental.
roxy_env=(
    env -u MESA_EXTENSION_OVERRIDE
    "MESA_EGL_LIBRARY=$roxy_runtime/lib/libEGL.dylib"
    "MESA_VULKAN_LIBRARY=$roxy_runtime/lib/libvulkan.1.dylib"
    "LIBGL_DRIVERS_PATH=$roxy_runtime/lib/dri"
    "VK_DRIVER_FILES=$roxy_runtime/share/vulkan/icd.d/kosmickrisp_icd.json"
    EGL_PLATFORM=surfaceless
    MESA_LOADER_DRIVER_OVERRIDE=zink
    MESA_KK_EXPERIMENTAL=custom_border
    MESA_SHADER_CACHE_DISABLE=true
    MESA_GL_VERSION_OVERRIDE=4.6
    MESA_GLSL_VERSION_OVERRIDE=460
)
roxy_jvm=(
    -XstartOnFirstThread
    --enable-native-access=ALL-UNNAMED
    -Djoml.nounsafe=true
    "-Dorg.lwjgl.opengl.libname=$roxy_runtime/lib/libGL.dylib"
    "-Dorg.lwjgl.egl.libname=$roxy_runtime/lib/libEGL.dylib"
    "-Dorg.lwjgl.glfw.libname=$roxy_runtime/lib/libglfw.3.dylib"
    "-Droxy.macos.nativePath=$roxy_natives"
    -Dvoxy.geometryBufferSizeOverrideMB=512
)

printf 'Roxy macOS graphics probe starting. Log: %s\n' "$roxy_graphics_log" >&2
if ! "${roxy_env[@]}" "$roxy_java" "${roxy_jvm[@]}" \
    "-Droxy.macos.probe.reportPath=$roxy_shader_reports" \
    -cp "$roxy_probe_classpath" GlCapabilityProbe "$roxy_voxy_jar" \
    >"$roxy_graphics_log" 2>&1; then
    printf 'Roxy macOS graphics probe failed; Minecraft was not started. See %s\n' "$roxy_graphics_log" >&2
    exit 1
fi

printf 'Roxy macOS graphics probe passed. Starting Minecraft with Zink.\n' >&2

# Preserve the launcher's original Java argument sequence and values exactly. Wrapper
# JVM options are prepended because Java must see them before the main class.
exec "${roxy_env[@]}" "$roxy_java" "${roxy_jvm[@]}" "${roxy_original_args[@]}"
