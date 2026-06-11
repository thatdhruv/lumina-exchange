import type { NextConfig } from "next";

const backendUrl = process.env.API_INTERNAL_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  output: "standalone",
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${backendUrl}/api/:path*`,
      },
      {
        source: "/actuator/:path*",
        destination: `${backendUrl}/actuator/:path*`,
      },
    ];
  },
};

export default nextConfig;
