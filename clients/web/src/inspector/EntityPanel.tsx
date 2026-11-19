import type { Resource } from '../resources/cache.ts';
import { SpriteIcon } from '../sprites/Sprite.tsx';
import { factsOf, nameOf, type Fact } from './facts.ts';

function FactList({ facts }: { facts: readonly Fact[] }) {
  return (
    <dl className="entity-facts">
      {facts.map((fact) => (
        <div key={fact.label} className="entity-fact" data-fact={fact.label}>
          <dt>{fact.label}</dt>
          <dd>{fact.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Id, version and children: there, but folded away. */
function ResourceDetails({ resource }: { resource: Resource }) {
  return (
    <details className="entity-details">
      <summary>id, version, children</summary>
      <dl className="entity-facts">
        <div className="entity-fact" data-fact="id">
          <dt>id</dt>
          <dd>
            <code>{resource.id}</code>
          </dd>
        </div>
        <div className="entity-fact" data-fact="version">
          <dt>version</dt>
          <dd className="resource-version">{resource.version}</dd>
        </div>
        <div className="entity-fact" data-fact="children">
          <dt>children</dt>
          <dd>{resource.children.length === 0 ? 'none' : resource.children.join(', ')}</dd>
        </div>
      </dl>
    </details>
  );
}

interface EntityPanelProps {
  resource: Resource;
  /** Marks the values as possibly out of date (a fetch for them failed). */
  stale?: boolean;
}

/**
 * The entity panel (#79): one component, used as the hover card and in the dock.
 * Every value is the server's; a missing one shows "—". The entity's icon (#208)
 * stands beside its name.
 */
export function EntityPanel({ resource, stale = false }: EntityPanelProps) {
  return (
    <div className="entity-panel" data-resource-id={resource.id}>
      <div className="entity-panel-head">
        {resource.resourceKind === 'entity' && <SpriteIcon entity={resource} />}
        <h3 className="entity-panel-title">
          {nameOf(resource)}
          {stale && <em> (stale)</em>}
        </h3>
      </div>
      <FactList facts={factsOf(resource)} />
      <ResourceDetails resource={resource} />
    </div>
  );
}
