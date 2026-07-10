import path from "node:path";
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Produces a minimal standalone server (server.js + only the deps it needs) for the
  // Docker image, matching the lean-runtime-image approach used by apps/backend-spring.
  output: "standalone",
  // Pin the workspace root: the repo has both a root bun.lock (the monorepo's package
  // manager) and this app's own package-lock.json (npm, scoped locally so `npm install`
  // doesn't pollute the bun-managed root) — without this, Next.js's lockfile inference
  // picks the wrong root.
  turbopack: {
    root: path.join(__dirname),
  },
};

export default nextConfig;
