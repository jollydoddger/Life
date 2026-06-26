// library.js — a tiny persistent library of saved scenes, kept in localStorage.
//
// Items are plain objects:
//   { id, title, url, thumbnail, kind: 'feed' | 'video', projection?, layout? }
// `kind` lets the loader decide whether to re-parse a deeplink or play directly;
// projection/layout are remembered for direct videos so they restore on reopen.

const KEY = 'orbit.library.v1';

export function load() {
  try { return JSON.parse(localStorage.getItem(KEY)) || []; }
  catch { return []; }
}

function persist(items) {
  try { localStorage.setItem(KEY, JSON.stringify(items)); } catch { /* private mode / quota */ }
}

// Stable id from the URL so the same scene can't be saved twice.
function idFor(url) {
  let h = 0;
  for (let i = 0; i < url.length; i++) h = (h * 31 + url.charCodeAt(i)) | 0;
  return 'i' + (h >>> 0).toString(36);
}

export function add(item) {
  if (!item || !item.url) return load();
  const items = load();
  const id = idFor(item.url);
  if (items.some((i) => i.id === id)) return items; // dedupe
  items.unshift({
    id,
    title: item.title || item.url,
    url: item.url,
    thumbnail: item.thumbnail || '',
    kind: item.kind || 'video',
    projection: item.projection,
    layout: item.layout,
  });
  persist(items);
  return items;
}

export function remove(id) {
  const items = load().filter((i) => i.id !== id);
  persist(items);
  return items;
}

export function has(url) {
  return url ? load().some((i) => i.id === idFor(url)) : false;
}
