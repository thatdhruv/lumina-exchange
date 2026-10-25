import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  // The SSE stream passes through the /api/v1 route handler; buffering it would defeat the point.
  compress: false,
  poweredByHeader: false,
};

export default nextConfig;
