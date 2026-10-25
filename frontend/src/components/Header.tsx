interface HeaderProps {
  connected: boolean;
}

export function Header({ connected }: HeaderProps) {
  return (
    <header className="mb-8 border-b border-surface-border pb-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.2em] text-accent">Lumina Exchange</p>
          <h1 className="mt-1 text-2xl font-bold tracking-tight text-white md:text-3xl">Trading Console</h1>
          <p className="mt-2 max-w-2xl text-sm text-gray-400">
            Live order entry, market depth and trades, streamed from the matching engine.
          </p>
        </div>
        <div
          className="flex items-center gap-2 rounded-full border border-surface-border px-3 py-1.5 text-xs"
          role="status"
        >
          <span className={`h-2 w-2 rounded-full ${connected ? "bg-bid" : "animate-pulse bg-yellow-500"}`} />
          {connected ? "Live" : "Connecting…"}
        </div>
      </div>
    </header>
  );
}
