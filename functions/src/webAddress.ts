/**
 * Port of `WebAddress.parse` in topics/…/WebAddress.kt: [text] as an http(s) address, with
 * `https://` added when it has no scheme, or null when it isn't a web address.
 */
export function parseWebAddress(text: string): string | null {
  const trimmed = text.trim();
  if (trimmed.length === 0 || /\s/.test(trimmed)) return null;
  let candidate: string;
  if (/^https?:\/\//i.test(trimmed)) {
    candidate = trimmed;
  } else if (SCHEMELESS.test(trimmed)) {
    candidate = `https://${trimmed}`;
  } else {
    return null;
  }
  try {
    return new URL(candidate).hostname.length > 0 ? candidate : null;
  } catch {
    return null;
  }
}

// host.tld with optional port and path; the scheme is added before parsing.
const SCHEMELESS = /^([a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}(:\d{1,5})?([/?#]\S*)?$/i;
