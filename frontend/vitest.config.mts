import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// .mts, not .ts: Vite loads a bare .ts config as CommonJS and warns about the ESM syntax
// in it. The extension is the fix rather than "type": "module" in package.json, which
// would change how every other config file in the project is loaded.
export default defineConfig({
  plugins: [react()],
  // Native since Vite 8, and the vite-tsconfig-paths plugin now warns that it is
  // redundant. Same resolution for "@/…", one fewer dependency.
  resolve: { tsconfigPaths: true },
  test: {
    environment: "jsdom",
    setupFiles: ["./vitest.setup.ts"],
    include: ["src/**/*.test.tsx", "src/**/*.test.ts"],
  },
});
