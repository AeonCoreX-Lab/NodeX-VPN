#!/usr/bin/env node
// npm/bin/platform.js
// Central platform detection logic.
// Returns the correct @aeoncorex/nodex-vpn-<platform> package name,
// and the path to the binary inside that package.

"use strict";

const path = require("path");

// Maps [process.platform, process.arch] → npm package suffix
// Matches the names used in package.json optionalDependencies.
const PLATFORM_MAP = {
  "linux-x64":    "linux-x64",
  "linux-arm64":  "linux-arm64",
  "darwin-x64":   "darwin-x64",
  "darwin-arm64": "darwin-arm64",
  "win32-x64":    "win32-x64",
  "win32-arm64":  "win32-arm64",
};

// Some systems report 'arm' instead of 'arm64' for 64-bit ARM
function normalizeArch(arch) {
  if (arch === "arm64" || arch === "aarch64") return "arm64";
  if (arch === "x64"   || arch === "x86_64")  return "x64";
  return arch;
}

/**
 * Returns the npm package suffix for the current platform,
 * or null if unsupported.
 */
function getPlatformPackage() {
  const plat = process.platform;
  const arch  = normalizeArch(process.arch);
  const key   = plat + "-" + arch;
  return PLATFORM_MAP[key] ? "@aeoncorex/nodex-vpn-" + PLATFORM_MAP[key] : null;
}

/**
 * Returns the absolute path to the nodex binary inside the given
 * platform package. Throws if the package is not installed.
 */
function getBinaryPath(packageName) {
  // resolve() throws if not found — that's intentional
  const pkgDir  = path.dirname(require.resolve(packageName + "/package.json"));
  const binName = process.platform === "win32" ? "nodex.exe" : "nodex";
  return path.join(pkgDir, "bin", binName);
}

module.exports = { getPlatformPackage, getBinaryPath, normalizeArch };
