#!/usr/bin/env node
// scripts/bump-version.js
//
// Atomically bumps the version in ALL version-carrying files:
//   • rust-core/Cargo.toml
//   • npm/package.json          (base package + optionalDependencies)
//   • npm/packages/*/package.json  (all 6 platform packages)
//
// Usage:
//   node scripts/bump-version.js 0.2.0
//   node scripts/bump-version.js           ← interactive prompt
//
// After running:
//   git add -A
//   git commit -m "chore: release v0.2.0"
//   git tag v0.2.0
//   git push origin main --tags

"use strict";

const fs   = require("fs");
const path = require("path");

// ── helpers ───────────────────────────────────────────────────────────────────

const ROOT = path.resolve(__dirname, "..");

function readFile(rel) {
  return fs.readFileSync(path.join(ROOT, rel), "utf8");
}
function writeFile(rel, content) {
  fs.writeFileSync(path.join(ROOT, rel), content, "utf8");
}
function readJson(rel) {
  return JSON.parse(readFile(rel));
}
function writeJson(rel, obj) {
  writeFile(rel, JSON.stringify(obj, null, 2) + "\n");
}

function getCurrentVersion() {
  const cargo = readFile("rust-core/Cargo.toml");
  const m = cargo.match(/^version\s*=\s*"([^"]+)"/m);
  return m ? m[1] : "unknown";
}

function validateSemver(v) {
  return /^\d+\.\d+\.\d+(-[a-zA-Z0-9._-]+)?$/.test(v);
}

// ── bump functions ────────────────────────────────────────────────────────────

function bumpCargo(newVer) {
  let content = readFile("rust-core/Cargo.toml");
  // Replace the first `version = "x.y.z"` line (the package version)
  content = content.replace(
    /^(version\s*=\s*)"[^"]+"/m,
    `$1"${newVer}"`
  );
  writeFile("rust-core/Cargo.toml", content);
  console.log(`  ✔ rust-core/Cargo.toml → ${newVer}`);
}

function bumpNpmBase(newVer) {
  const pkg = readJson("npm/package.json");
  pkg.version = newVer;
  for (const dep of Object.keys(pkg.optionalDependencies || {})) {
    pkg.optionalDependencies[dep] = newVer;
  }
  writeJson("npm/package.json", pkg);
  console.log(`  ✔ npm/package.json → ${newVer}`);
}

function bumpNpmPlatforms(newVer) {
  const pkgsDir = path.join(ROOT, "npm/packages");
  if (!fs.existsSync(pkgsDir)) {
    console.warn("  ⚠  npm/packages/ not found — skipping platform packages");
    return;
  }
  for (const dir of fs.readdirSync(pkgsDir)) {
    const rel = `npm/packages/${dir}/package.json`;
    const full = path.join(ROOT, rel);
    if (!fs.existsSync(full)) continue;
    const pkg = readJson(rel);
    pkg.version = newVer;
    writeJson(rel, pkg);
    console.log(`  ✔ ${rel} → ${newVer}`);
  }
}

// ── entry point ───────────────────────────────────────────────────────────────

async function main() {
  const currentVer = getCurrentVersion();
  let newVer = process.argv[2];

  if (!newVer) {
    // Interactive prompt (Node built-in readline)
    const rl = require("readline").createInterface({
      input:  process.stdin,
      output: process.stdout,
    });
    newVer = await new Promise((resolve) => {
      rl.question(
        `\n  Current version: ${currentVer}\n  New version:     `,
        (ans) => { rl.close(); resolve(ans.trim()); }
      );
    });
  }

  // Strip leading "v" if provided (v0.2.0 → 0.2.0)
  newVer = newVer.replace(/^v/, "");

  if (!validateSemver(newVer)) {
    console.error(`\n  ✘ Invalid semver: "${newVer}"`);
    console.error(`    Expected format: 0.2.0  or  1.0.0-beta.1\n`);
    process.exit(1);
  }

  if (newVer === currentVer) {
    console.error(`\n  ✘ Version "${newVer}" is already current.\n`);
    process.exit(1);
  }

  console.log(`\n  Bumping ${currentVer} → ${newVer}\n`);

  bumpCargo(newVer);
  bumpNpmBase(newVer);
  bumpNpmPlatforms(newVer);

  console.log(`
  ✔ All files updated.

  Next steps:
    1. Update RELEASES.md — add section for [${newVer}]
    2. git add -A
    3. git commit -m "chore: release v${newVer}"
    4. git tag v${newVer}
    5. git push origin main --tags

  GitHub Actions will automatically:
    • Build CLI for 7 platforms
    • Publish to npm
    • Create GitHub Release
`);
}

main().catch((e) => { console.error(e); process.exit(1); });
