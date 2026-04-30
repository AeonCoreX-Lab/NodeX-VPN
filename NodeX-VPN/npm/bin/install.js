#!/usr/bin/env node
// npm/bin/install.js
// Runs after `npm install` — verifies the correct platform binary was installed.
// If the optional platform package is missing (unsupported platform or
// optional dep skipped), prints a helpful error and exits 1.

"use strict";

const { getBinaryPath, getPlatformPackage } = require("./platform");
const fs   = require("fs");
const path = require("path");

function main() {
  const pkg = getPlatformPackage();

  if (!pkg) {
    console.error(
      "\n  ✘  NodeX VPN — unsupported platform\n" +
      "     Platform: " + process.platform + " / " + process.arch + "\n" +
      "     Supported: linux-x64, linux-arm64, darwin-x64, darwin-arm64,\n" +
      "                win32-x64, win32-arm64\n\n" +
      "     To install from source:\n" +
      "       cargo install --git https://github.com/AeonCoreX/NodeX-VPN\n" +
      "                     --bin nodex --features cli\n"
    );
    process.exit(1);
  }

  let binPath;
  try {
    binPath = getBinaryPath(pkg);
  } catch (e) {
    console.error(
      "\n  ✘  NodeX VPN — platform package not installed\n" +
      "     Expected package: " + pkg + "\n\n" +
      "     Try:\n" +
      "       npm install -g @aeoncorex/nodex-vpn --force\n\n" +
      "     Or install from source:\n" +
      "       cargo install --git https://github.com/AeonCoreX/NodeX-VPN\n" +
      "                     --bin nodex --features cli\n"
    );
    process.exit(1);
  }

  if (!fs.existsSync(binPath)) {
    console.error("\n  ✘  NodeX VPN binary not found at: " + binPath + "\n");
    process.exit(1);
  }

  // Make binary executable on Unix
  if (process.platform !== "win32") {
    try {
      fs.chmodSync(binPath, 0o755);
    } catch (_) {}
  }

  console.log("\n  ✔  NodeX VPN installed successfully");
  console.log("     Run: nodex version\n");
}

main();
