import { createHash } from "node:crypto";

/**
 * Port of `linkUrlKey()` in links/…/LinkUrlKey.kt: the identity of a link's address. The app and
 * these functions must derive the same key for the same URL, or one page is saved twice
 * (docs/CLOUD_SYNC_PLAN.md › Identity). `test/linkKey.test.ts` holds the shared vectors.
 *
 * Parses the way `java.net.URL` does rather than with WHATWG `URL`, which would punycode hosts
 * and percent-encode paths that the app keeps as typed.
 */
export function linkUrlKey(url: string): string {
  const trimmed = url.trim();
  const parsed = parseHttpUrl(trimmed);
  if (!parsed) return trimmed;

  const host = parsed.host.toLowerCase().replace(/^www\./, "");
  const port = parsed.port !== -1 && parsed.port !== 80 && parsed.port !== 443 ? `:${parsed.port}` : "";
  const path = parsed.path.replace(/\/+$/, "");
  const params = (parsed.query ?? "")
    .split("&")
    .filter((param) => param.length > 0 && !isTrackingParam(param.split("=")[0]));
  const query = params.length > 0 ? `?${params.join("&")}` : "";
  return host + port + path + query;
}

/** First 32 hex chars of SHA-256 over the UTF-8 [urlKey]: a link's Firestore document id. */
export function linkUid(urlKey: string): string {
  return createHash("sha256").update(urlKey, "utf8").digest("hex").slice(0, 32);
}

function isTrackingParam(name: string): boolean {
  const lower = name.toLowerCase();
  return lower.startsWith("utm_") || lower === "fbclid" || lower === "gclid";
}

interface ParsedUrl {
  host: string;
  /** -1 when the URL names none. */
  port: number;
  path: string;
  query: string | null;
}

/** An http(s) URL split as `java.net.URL` splits it; null for anything else or a bad port. */
function parseHttpUrl(spec: string): ParsedUrl | null {
  const scheme = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(spec);
  if (!scheme) return null;
  const protocol = scheme[1].toLowerCase();
  if (protocol !== "http" && protocol !== "https") return null;

  let rest = spec.slice(scheme[0].length);
  const hash = rest.indexOf("#");
  if (hash >= 0) rest = rest.slice(0, hash);

  let authority = "";
  if (rest.startsWith("//")) {
    rest = rest.slice(2);
    const end = firstIndexOf(rest, ["/", "?"]);
    authority = end < 0 ? rest : rest.slice(0, end);
    rest = end < 0 ? "" : rest.slice(end);
  }

  let host = authority.slice(authority.lastIndexOf("@") + 1);
  let port = -1;
  const portStart = host.startsWith("[") ? host.indexOf(":", host.indexOf("]")) : host.indexOf(":");
  if (portStart >= 0) {
    const digits = host.slice(portStart + 1);
    if (digits.length > 0) {
      if (!/^\d+$/.test(digits)) return null;
      port = Number(digits);
    }
    host = host.slice(0, portStart);
  }

  const queryStart = rest.indexOf("?");
  return {
    host,
    port,
    path: queryStart < 0 ? rest : rest.slice(0, queryStart),
    query: queryStart < 0 ? null : rest.slice(queryStart + 1),
  };
}

function firstIndexOf(text: string, chars: string[]): number {
  const found = chars.map((c) => text.indexOf(c)).filter((i) => i >= 0);
  return found.length > 0 ? Math.min(...found) : -1;
}
