#!/usr/bin/env bash

set -euo pipefail

SOURCE_DIR="${1:?missing source dir}"
OUTPUT_DIR="${2:?missing output dir}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOCAL_SLANG_DIR="$PROJECT_DIR/.meshfish-tools/slang"

mkdir -p "$OUTPUT_DIR"

SLANGC_BIN="${SLANGC:-}"
if [[ -z "$SLANGC_BIN" && -x "$LOCAL_SLANG_DIR/bin/slangc" ]]; then
    SLANGC_BIN="$LOCAL_SLANG_DIR/bin/slangc"
fi
if [[ -z "$SLANGC_BIN" ]]; then
    SLANGC_BIN="$(command -v slangc || true)"
fi

GLSLANG_BIN="${GLSLANG_VALIDATOR:-}"
if [[ -z "$GLSLANG_BIN" ]]; then
    GLSLANG_BIN="$(command -v glslangValidator || true)"
fi
if [[ -z "$GLSLANG_BIN" ]]; then
    GLSLANG_BIN="$(command -v glslang || true)"
fi

COMPILER_KIND=""
if [[ -n "$SLANGC_BIN" ]]; then
    COMPILER_KIND="slangc"
elif [[ -n "$GLSLANG_BIN" ]]; then
    echo "[Meshfish] glslang is available, but it cannot compile the Slang mesh/task stages used by Meshfish." >&2
    echo "[Meshfish] Install shader-slang, set \$SLANGC, or place a local toolchain in .meshfish-tools/slang." >&2
    exit 0
else
    echo "[Meshfish] No shader Slang compiler found. Install shader-slang or set \$SLANGC." >&2
    echo "[Meshfish] Note: the Arch package named 'slang' is the S-Lang scripting language, not shader-slang." >&2
    exit 0
fi

if [[ "$SLANGC_BIN" == "$LOCAL_SLANG_DIR/bin/slangc" && -d "$LOCAL_SLANG_DIR/lib" ]]; then
    export LD_LIBRARY_PATH="$LOCAL_SLANG_DIR/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi

compile_stage() {
    local source_file="$1"
    local slang_stage="$2"
    local glslang_stage="$3"
    local output_file="$4"

    if [[ ! -f "$source_file" ]]; then
        rm -f "$output_file"
        echo "[Meshfish] Missing shader source: $source_file" >&2
        return
    fi

    if [[ ! -s "$source_file" ]]; then
        rm -f "$output_file"
        echo "[Meshfish] Skipping empty shader source: $source_file" >&2
        return
    fi

    "$SLANGC_BIN" \
        "$source_file" \
        -I "$SOURCE_DIR" \
        -entry main \
        -stage "$slang_stage" \
        -target spirv \
        -profile spirv_1_6 \
        -capability SPV_EXT_mesh_shader \
        -o "$output_file"

    echo "[Meshfish] Compiled $(basename "$source_file") -> $(basename "$output_file")"
}

compile_stage "$SOURCE_DIR/tool.slang" task task "$OUTPUT_DIR/tool.spv"
compile_stage "$SOURCE_DIR/mesh.slang" mesh mesh "$OUTPUT_DIR/mesh.spv"
compile_stage "$SOURCE_DIR/scene_mesh.slang" mesh mesh "$OUTPUT_DIR/scene_mesh.spv"
compile_stage "$SOURCE_DIR/fragment.slang" fragment frag "$OUTPUT_DIR/fragment.spv"
compile_stage "$SOURCE_DIR/scene_fragment.slang" fragment frag "$OUTPUT_DIR/scene_fragment.spv"
