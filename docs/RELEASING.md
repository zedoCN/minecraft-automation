# Releasing Minecraft Automation

Releases are GitHub-only. The upstream Modrinth project is not this fork's publication target.
`modrinth_id` is intentionally empty; do not reuse the upstream project ID or token.

## Before publishing

1. Confirm repository owner/name and public/private visibility. Update source/support links if
   they differ from `zedoCN/minecraft-automation`.
2. Review working tree and history for credentials, private data and third-party files.
   Never add `local/`, worlds, runtime configuration or downloaded mods.
3. Align `gradle.properties` and `mcp-server/package.json` versions; update the lockfile through
   npm if the package version changes. MCP handshake version comes from package metadata.
4. Update `CHANGELOG.md`, typecheck/build MCP, and build `:26.2:build`.
5. Run relevant automated and in-game checks; record exactly what ran. Other version nodes are
   not supported merely because their configuration exists.

## Workflow

After approval, push a `vX.Y.Z` or `vX.Y.Z-suffix` tag matching `mod_version`.
The Release workflow builds Minecraft 26.2 and attaches non-sources jars to a GitHub Release.
A suffix creates a prerelease. Manual dispatch accepts the version without `v` and checks out
that existing tag, so retrying with an updated workflow never silently builds a different commit.

No Modrinth, npm or upstream publication is performed. Ignored test servers and worlds are not
packaged. Build the MCP service from source using its README.
Use the ordinary Build workflow to check CI before creating a release.
