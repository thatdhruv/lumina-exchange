"use client";

import { api } from "@/lib/api";
import { useEffect, useState } from "react";

export function Header() {
  const [healthy, setHealthy] = useState<boolean | null>(null);

  useEffect(() => {
    async function check() {
      try {
        const health = await api.health();
        setHealthy(health.status === "UP");
      } catch {
        setHealthy(false);
      }
    }
    check();
    const timer = setInterval(check, 5000);
    return () => clearInterval(timer);
  }, []);

  return (
    <header className="mb-8 border-b border-surface-border pb-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.2em] text-accent">Lumina Exchange</p>
          <h1 className="mt-1 text-2xl font-bold tracking-tight text-white md:text-3xl">
            Trading Console
          </h1>
          <p className="mt-2 max-w-2xl text-sm text-gray-400">
            Real-time order entry, market depth, and trade history across supported symbols.
          </p>
        </div>
        <div className="flex items-center gap-2 rounded-full border border-surface-border px-3 py-1.5 text-xs">
          <span
            className={`h-2 w-2 rounded-full ${
              healthy === null ? "bg-yellow-500" : healthy ? "bg-bid" : "bg-ask"
            }`}
          />
          {healthy === null ? "Checking API…" : healthy ? "API healthy" : "API unreachable"}
        </div>
      </div>
    </header>
  );
}
