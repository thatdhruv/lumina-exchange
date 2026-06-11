import type { ReactNode } from "react";

interface PanelProps {
  title: string;
  subtitle?: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}

export function Panel({ title, subtitle, action, children, className = "" }: PanelProps) {
  return (
    <section className={`panel flex flex-col ${className}`}>
      <header className="flex items-start justify-between gap-3 border-b border-surface-border px-4 py-3">
        <div>
          <h2 className="text-sm font-semibold tracking-wide text-gray-100">{title}</h2>
          {subtitle && <p className="mt-0.5 text-xs text-gray-500">{subtitle}</p>}
        </div>
        {action}
      </header>
      <div className="flex-1 p-4">{children}</div>
    </section>
  );
}
