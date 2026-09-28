import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // The Docker image copies a self-contained server rather than the whole node_modules
  // tree, which is what keeps the runtime stage small enough to be worth having.
  output: "standalone",
};

export default nextConfig;
