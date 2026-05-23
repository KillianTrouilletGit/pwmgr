import { defineConfig } from "vite";
import { resolve } from "node:path";
import { cpSync } from "node:fs";

/**
 * Multi-entry build for an MV3 extension:
 *   - background.ts → service worker
 *   - content.ts    → content script (no DOM module wrapping)
 *   - popup.ts      → popup page script
 *
 * Vite's default `build` is geared toward single-page apps. We use the `lib` mode is not
 * a fit here either; we drive a manual rollupOptions config. After build, manifest.json
 * and popup.html are copied verbatim into dist/.
 */
export default defineConfig({
  build: {
    outDir: "dist",
    emptyOutDir: true,
    minify: false, // easier to read in DevTools when debugging
    rollupOptions: {
      input: {
        background: resolve(__dirname, "src/background.ts"),
        content: resolve(__dirname, "src/content.ts"),
        popup: resolve(__dirname, "src/popup.ts"),
      },
      output: {
        entryFileNames: "[name].js",
        // Content scripts cannot use ES modules — bundle everything inline.
        format: "iife",
        inlineDynamicImports: false,
      },
    },
  },
  plugins: [
    {
      name: "copy-static-assets",
      closeBundle() {
        cpSync(resolve(__dirname, "manifest.json"), resolve(__dirname, "dist/manifest.json"));
        cpSync(resolve(__dirname, "popup.html"), resolve(__dirname, "dist/popup.html"));
      },
    },
  ],
});
