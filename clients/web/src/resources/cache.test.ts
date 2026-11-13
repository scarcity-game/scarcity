import { describe, expect, it, vi } from 'vitest';
import { ResourceStore, type Resource } from './cache.ts';

const res = (
  id: string,
  version: number,
  state: Record<string, unknown> = {},
  children: string[] = [],
): Resource => ({
  id,
  type: id.split(':')[0] ?? '',
  version,
  children,
  state,
});

describe('ResourceStore', () => {
  describe('applyPatch', () => {
    it('with children replaces the list', () => {
      const store = new ResourceStore();
      const initialResource = res('item:123', 1, {}, ['child1', 'child2']);
      store.put(initialResource);

      const patched = store.applyPatch('item:123', 2, { foo: 'bar' }, ['newChild1', 'newChild2']);

      expect(patched).toBe(true);
      const updated = store.get('item:123')!;
      expect(updated.children).toEqual(['newChild1', 'newChild2']);
    });

    it('without children keeps it', () => {
      const store = new ResourceStore();
      const initialResource = res('item:123', 1, {}, ['child1', 'child2']);
      store.put(initialResource);

      const patched = store.applyPatch('item:123', 2, { foo: 'bar' });

      expect(patched).toBe(true);
      const updated = store.get('item:123')!;
      expect(updated.children).toEqual(['child1', 'child2']);
    });
  });

  describe('remove', () => {
    it('drops the resource and returns true and notifies once', () => {
      const store = new ResourceStore();
      const resource = res('item:123', 1, { foo: 'bar' });
      store.put(resource);

      const listener = vi.fn();
      store.subscribe(listener);

      const removed = store.remove('item:123');

      expect(removed).toBe(true);
      expect(store.get('item:123')).toBeUndefined();
      expect(listener).toHaveBeenCalledTimes(1);
    });

    it('of unknown id returns false and does not notify', () => {
      const store = new ResourceStore();
      const listener = vi.fn();
      store.subscribe(listener);

      const removed = store.remove('item:123');

      expect(removed).toBe(false);
      expect(listener).toHaveBeenCalledTimes(0);
    });
  });

  describe('batch', () => {
    it('with two puts notifies subscribers exactly once', () => {
      const store = new ResourceStore();
      const listener = vi.fn();
      store.subscribe(listener);

      store.batch(() => {
        store.put(res('item:123', 1, { foo: 'bar' }));
        store.put(res('item:456', 1, { baz: 'qux' }));
      });

      expect(listener).toHaveBeenCalledTimes(1);
    });
  });

  describe('ids', () => {
    it('lists the ids', () => {
      const store = new ResourceStore();
      store.put(res('item:123', 1, {}));
      store.put(res('item:456', 1, {}));

      expect(store.ids()).toEqual(['item:123', 'item:456']);
    });
  });
});
