import type { ReactNode } from 'react'

export type IconName = 'contracts' | 'activity' | 'findings' | 'grid' | 'list' | 'search'

export function WorkspaceIcon({ name }: { name: IconName }) {
  return <span className={'workspace-icon icon-' + name} aria-hidden="true" />
}

export function WorkspaceHero({ title, description, children }: {
  title: string; description?: string; children?: ReactNode
}) {
  return <header className="workspace-hero">
    <div className="hero-copy">
      <h1>{title}</h1>
      {description && <p>{description}</p>}
      {children && <div className="hero-actions">{children}</div>}
    </div>
    <div className="hero-signal" aria-hidden="true">
      <div className="hero-orbit hero-orbit-outer" />
      <div className="hero-orbit hero-orbit-inner" />
      <div className="hero-node hero-node-contract"><WorkspaceIcon name="contracts" /></div>
      <div className="hero-node hero-node-code">{'{ }'}</div>
      <div className="hero-node hero-node-activity"><WorkspaceIcon name="activity" /></div>
      <i className="hero-spark spark-first" /><i className="hero-spark spark-second" /><i className="hero-spark spark-third" />
    </div>
  </header>
}
