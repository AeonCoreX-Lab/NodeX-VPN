#!/usr/bin/env node
// npm/bin/run.js
// Entry point for the `nodex` bin command.
// Resolves the platform-specific binary and executes it,
// passing through all CLI arguments and environment variables.

"use strict";

const { spawnSync }            = require("child_process");
const { getPlatformPackage, getBinaryPath } = require("./platform");
const fs   = require("fs");
const path = require("path");

function main() {
  const pkg = getPlatformPackage();

  if (!pkg) {
    console.error(
      "✘  NodeX VPN is not supported on this platform (" +
      process.platform + "/" + process.arch + ").\n" +
      "   Supported: Linux x64/arm64, macOS x64/arm64, Windows x64/arm64"
    );
    process.exit(1);
  }

  let binPath;
  try {
    binPath = getBinaryPath(pkg);
  } catch (_) {
    console.error(
      "✘  NodeX VPN platform package not found.\n" +
      "   Try: npm install -g @aeoncorex/nodex-vpn --force\n" +
      "   Or:  npx @aeoncorex/nodex-vpn@latest version"
    );
    process.exit(1);
  }

  if (!fs.existsSync(binPath)) {
    console.error("✘  Binary not found: " + binPath);
    process.exit(1);
  }

  // Ensure binary is executable
  if (process.platform !== "win32") {
    try { fs.chmodSync(binPath, 0o755); } catch (_) {}
  }

  // Spawn the binary, inherit all I/O streams and env
  const result = spawnSync(binPath, process.argv.slice(2), {
    stdio: "inherit",
    env:   process.env,
  });

  // Propagate exit code or signal
  if (result.signal) {
    process.kill(process.pid, result.signal);
  } else {
    process.exit(result.status ?? 0);
  }
}

main();
