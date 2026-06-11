"use client";

import { api } from "@/lib/api";
import { useEffect, useState } from "react";

const STACK = [
  { label: "Frontend", value: "Next.js 15 · TypeScript · React" },
  { label: "Backend", value: "Spring Boot 3 · Java 21 · gRPC" },
  { label: "Events", value: "Apache Kafka (KRaft)" },
  { label: "State", value: "Redis trade ledger" },
  { label: "Ops", value: "Docker · Kubernetes · Helm · Prometheus" },
];

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
            Enterprise Trading Console
          </h1>
          <p className="mt-2 max-w-2xl text-sm text-gray-400">
            Full-stack demo — Next.js UI over a Spring Boot HFT engine with Kafka event streaming,
            Redis persistence, and cloud-native deployment.
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
      <dl className="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        {STACK.map((item) => (
          <div key={item.label} className="rounded-lg border border-surface-border/80 bg-surface-raised/50 px-3 py-2">
            <dt className="text-[10px] uppercase tracking-wider text-gray-500">{item.label}</dt>
            <dd className="mt-0.5 text-xs text-gray-300">{item.value}</dd>
          </div>
        ))}
      </dl>
    </header>
  );
}
