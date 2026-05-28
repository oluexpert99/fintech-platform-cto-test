import type { ReactNode } from "react";

type Props = { title: string; children?: ReactNode };

export function EmptyState({ title, children }: Props) {
  return (
    <div className="empty-state">
      <p className="empty-state__title">{title}</p>
      {children && <div className="empty-state__body">{children}</div>}
    </div>
  );
}
