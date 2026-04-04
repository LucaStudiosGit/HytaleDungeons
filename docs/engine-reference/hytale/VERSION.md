# Hytale Modding API — Version Reference

| Field | Value |
|-------|-------|
| **Engine** | Hytale (server modding API) |
| **Build** | 2026.02.19-1a311a592 |
| **Patchline** | release |
| **Java Version** | 25 (runtime), 21 (build target) |
| **Project Pinned** | 2026-04-01 |
| **Last Docs Verified** | 2026-04-01 |
| **LLM Knowledge Cutoff** | May 2025 |

## Knowledge Gap Warning

Hytale's modding API is not covered in the LLM's training data. The API
is proprietary and only available through the local HytaleServer.jar.
Always reference the actual JAR classes and HyUI source for API guidance.

## Key Dependencies

| Dependency | Source | Usage |
|------------|--------|-------|
| HytaleServer.jar | Local install (`$HYTALE_HOME/install/release/...`) | Compile-only + runtime |
| HyUI | CurseMaven (`curse.maven:hyui-1431415:7713977`) | Compile-only (UI framework) |

## Build Notes

- Compile against the **exact local HytaleServer.jar** — never use a Maven artifact
- The `com.hypixel.hytale:Server` module is explicitly excluded from all configurations
- Shadow plugin produces a fat JAR for deployment
- Asset pack is included (`includes_pack=true`)

## How to Verify APIs

Since the LLM has no training data on Hytale's modding API:
1. Decompile or browse HytaleServer.jar classes for available APIs
2. Reference HyUI source for UI patterns
3. Check the official Hytale modding documentation if available
4. Test against the local server with `gradle runServer`
