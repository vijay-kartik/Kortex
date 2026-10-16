/**
 * Port of `PageMetadataFetcher` in links/…/tagging: the title and share image of a page, read from
 * its `<head>`. Used when a link is saved through a function without them, so the app shows it
 * like a link saved on the device.
 *
 * The address comes from the caller, so every hop is checked to resolve to a public host before it
 * is fetched. Any failure gives an empty result: saving a link never depends on the page.
 */

import { lookup } from "node:dns/promises";
import { isIP } from "node:net";

export interface PageMetadata {
  title: string | null;
  imageUrl: string | null;
}

const EMPTY: PageMetadata = { title: null, imageUrl: null };

const FETCH_TIMEOUT_MS = 6_000;
const MAX_BODY_BYTES = 512_000;
const MAX_REDIRECTS = 3;
const MAX_FIELD_LENGTH = 2_000;
// Same browser-like agent as the app: many sites serve no tags to unknown clients.
const USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

/** What the fetch depends on, so tests can run it without the network. */
export interface PageDeps {
  fetchPage: typeof fetch;
  resolve: (hostname: string) => Promise<string[]>;
}

const defaultDeps: PageDeps = {
  fetchPage: (input, init) => fetch(input, init),
  resolve: async (hostname) => (await lookup(hostname, { all: true })).map((a) => a.address),
};

export async function fetchPageMetadata(url: string, deps: PageDeps = defaultDeps): Promise<PageMetadata> {
  try {
    const page = await fetchHtml(url, deps);
    return page === null ? EMPTY : parsePageMetadata(page.html, page.finalUrl);
  } catch {
    return EMPTY;
  }
}

async function fetchHtml(startUrl: string, deps: PageDeps): Promise<{ html: string; finalUrl: string } | null> {
  let current = startUrl;
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    if (!(await isPublicWebAddress(current, deps))) return null;
    const response = await deps.fetchPage(current, {
      redirect: "manual",
      headers: { "User-Agent": USER_AGENT, Accept: "text/html,application/xhtml+xml" },
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
    });
    if (response.status >= 300 && response.status < 400) {
      const location = response.headers.get("location");
      if (location === null) return null;
      current = new URL(location, current).toString();
      continue;
    }
    if (!response.ok) return null;
    const type = response.headers.get("content-type") ?? "";
    if (type !== "" && !/html/i.test(type)) return null;
    return { html: await readCapped(response), finalUrl: current };
  }
  return null;
}

async function readCapped(response: Response): Promise<string> {
  if (response.body === null) return "";
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  while (size < MAX_BODY_BYTES) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    size += value.length;
  }
  await reader.cancel().catch(() => undefined);
  return Buffer.concat(chunks).subarray(0, MAX_BODY_BYTES).toString("utf8");
}

async function isPublicWebAddress(address: string, deps: PageDeps): Promise<boolean> {
  const url = new URL(address);
  if (url.protocol !== "http:" && url.protocol !== "https:") return false;
  const host = url.hostname.replace(/^\[|\]$/g, "");
  const addresses = isIP(host) !== 0 ? [host] : await deps.resolve(host);
  return addresses.length > 0 && addresses.every((a) => !isPrivateAddress(a));
}

/** Loopback, private, link-local (incl. the cloud metadata address), CGNAT, multicast and reserved ranges. */
export function isPrivateAddress(address: string): boolean {
  const mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/i.exec(address);
  if (mapped !== null) return isPrivateAddress(mapped[1]);
  if (isIP(address) === 4) {
    const [a, b] = address.split(".").map(Number);
    return (
      a === 0 || a === 10 || a === 127 || a >= 224 ||
      (a === 100 && b >= 64 && b <= 127) ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) ||
      (a === 192 && b === 0) ||
      (a === 198 && (b === 18 || b === 19))
    );
  }
  const v6 = address.toLowerCase();
  return v6 === "::" || v6 === "::1" || /^f[cd]/.test(v6) || /^fe[89ab]/.test(v6) || v6.startsWith("ff");
}

/** Title and image from the page's tags, in the order the app prefers them. */
export function parsePageMetadata(html: string, pageUrl: string): PageMetadata {
  const head = html.slice(0, MAX_BODY_BYTES);
  const metas = metaTags(head);
  const title =
    firstOf(metas, ["og:title", "twitter:title"]) ?? clean(decodeEntities(/<title[^>]*>([\s\S]*?)<\/title>/i.exec(head)?.[1] ?? ""));
  const image = firstOf(metas, ["og:image", "og:image:secure_url", "og:image:url", "twitter:image", "twitter:image:src"]);
  return { title: title === null ? null : title.slice(0, MAX_FIELD_LENGTH), imageUrl: image === null ? null : absoluteWebUrl(image, pageUrl) };
}

function metaTags(html: string): Map<string, string> {
  const found = new Map<string, string>();
  for (const tag of html.match(/<meta\b[^>]*>/gi) ?? []) {
    const attrs = attributes(tag);
    const key = (attrs.get("property") ?? attrs.get("name"))?.toLowerCase();
    const content = clean(decodeEntities(attrs.get("content") ?? ""));
    if (key !== undefined && content !== null && !found.has(key)) found.set(key, content);
  }
  return found;
}

function attributes(tag: string): Map<string, string> {
  const attrs = new Map<string, string>();
  for (const m of tag.matchAll(/([a-zA-Z_:][-\w:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))/g)) {
    attrs.set(m[1].toLowerCase(), m[2] ?? m[3] ?? m[4] ?? "");
  }
  return attrs;
}

function firstOf(metas: Map<string, string>, keys: string[]): string | null {
  for (const key of keys) {
    const value = metas.get(key);
    if (value !== undefined) return value;
  }
  return null;
}

function clean(text: string): string | null {
  const trimmed = text.replace(/\s+/g, " ").trim();
  return trimmed.length === 0 ? null : trimmed;
}

/** Resolves [value] against the page and keeps only http(s) addresses. */
function absoluteWebUrl(value: string, pageUrl: string): string | null {
  try {
    const url = new URL(value, pageUrl);
    return url.protocol === "http:" || url.protocol === "https:" ? url.toString() : null;
  } catch {
    return null;
  }
}

const ENTITIES: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " };

function decodeEntities(text: string): string {
  return text.replace(/&(?:#(\d+)|#x([0-9a-f]+)|([a-z]+));/gi, (whole, dec, hex, name) => {
    if (name !== undefined) return ENTITIES[name.toLowerCase()] ?? whole;
    const code = dec !== undefined ? Number(dec) : parseInt(hex, 16);
    return code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : whole;
  });
}
