/**
 * Multi-entry IIFE build for the MV3 extension.
 *
 * Why this script instead of a single vite.config.ts: Rollup refuses
 * `format: "iife"` whenever the build has more than one `input` entry,
 * regardless of whether the entries actually share code. By driving Vite's
 * programmatic API in a loop, each entry becomes its own single-input
 * build → IIFE is valid.
 *
 * MV3 content scripts cannot be ES modules (the OS isolated world has no
 * loader), so IIFE is the only viable shared format across our three
 * entry points.
 */
import { build } from "vite";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { cpSync } from "node:fs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, "..");

const entries = ["background", "content", "popup"];

for (let i = 0; i < entries.length; i++) {
  const entry = entries[i];
  await build({
    root,
    configFile: false,
    logLevel: "info",
    build: {
      outDir: "dist",
      // Only empty the output dir on the first pass — subsequent passes append.
      emptyOutDir: i === 0,
      minify: false,
      rollupOptions: {
        input: resolve(root, `src/${entry}.ts`),
        output: {
          format: "iife",
          entryFileNames: `${entry}.js`,
        },
      },
    },
  });
}

// Copy non-code assets after the last build pass.
cpSync(resolve(root, "manifest.json"), resolve(root, "dist/manifest.json"));
cpSync(resolve(root, "popup.html"), resolve(root, "dist/popup.html"));

console.log("\n✅ Extension built to browser-extension/dist/");
