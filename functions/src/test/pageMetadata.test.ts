import assert from "node:assert/strict";
import { test } from "node:test";
import { fetchPageMetadata, isPrivateAddress, PageDeps, parsePageMetadata } from "../pageMetadata";

const html = (head: string) => `<html><head>${head}</head><body><p>hi</p></body></html>`;

function deps(pages: Record<string, Response>, resolved: Record<string, string[]> = {}): PageDeps & { fetched: string[] } {
  const fetched: string[] = [];
  return {
    fetched,
    fetchPage: async (input) => {
      const url = String(input);
      fetched.push(url);
      const page = pages[url];
      return page === undefined ? new Response("", { status: 404 }) : page;
    },
    resolve: async (host) => resolved[host] ?? ["93.184.216.34"],
  };
}

const page = (body: string) => new Response(body, { status: 200, headers: { "content-type": "text/html; charset=utf-8" } });

test("og tags win over the title element", () => {
  const meta = parsePageMetadata(
    html(`<title>Plain</title><meta property="og:title" content="Shared &amp; loved"><meta property="og:image" content="https://cdn.example.com/a.png">`),
    "https://example.com/post",
  );
  assert.deepEqual(meta, { title: "Shared & loved", imageUrl: "https://cdn.example.com/a.png" });
});

test("falls back to title element and twitter image, resolving relative addresses", () => {
  const meta = parsePageMetadata(html(`<title>\n  Plain  title </title><meta name="twitter:image" content='/img/a.png'>`), "https://example.com/post/1");
  assert.deepEqual(meta, { title: "Plain title", imageUrl: "https://example.com/img/a.png" });
});

test("non-web images and missing tags give nulls", () => {
  assert.deepEqual(parsePageMetadata(html(`<meta property="og:image" content="data:image/png;base64,AAAA">`), "https://example.com"), { title: null, imageUrl: null });
});

test("fetches title and image from a page", async () => {
  const d = deps({ "https://example.com/p": page(html(`<meta property="og:title" content="T"><meta property="og:image" content="/i.png">`)) });
  assert.deepEqual(await fetchPageMetadata("https://example.com/p", d), { title: "T", imageUrl: "https://example.com/i.png" });
});

test("follows a redirect to a public host", async () => {
  const d = deps({
    "https://short.example/x": new Response(null, { status: 301, headers: { location: "https://example.com/real" } }),
    "https://example.com/real": page(html(`<title>Real</title>`)),
  });
  assert.equal((await fetchPageMetadata("https://short.example/x", d)).title, "Real");
});

test("never fetches a host that resolves to a private address", async () => {
  const d = deps({ "http://internal.example/": page(html(`<title>Secret</title>`)) }, { "internal.example": ["10.0.0.5"] });
  assert.deepEqual(await fetchPageMetadata("http://internal.example/", d), { title: null, imageUrl: null });
  assert.deepEqual(d.fetched, []);
});

test("never fetches an IP literal such as the metadata server", async () => {
  const d = deps({});
  await fetchPageMetadata("http://169.254.169.254/latest/meta-data", d);
  await fetchPageMetadata("http://[::1]/", d);
  assert.deepEqual(d.fetched, []);
});

test("refuses a redirect into a private address", async () => {
  const d = deps({ "https://example.com/r": new Response(null, { status: 302, headers: { location: "http://127.0.0.1/admin" } }) });
  assert.deepEqual(await fetchPageMetadata("https://example.com/r", d), { title: null, imageUrl: null });
  assert.deepEqual(d.fetched, ["https://example.com/r"]);
});

test("failures and non-html responses give an empty result", async () => {
  const d = deps({ "https://example.com/img": new Response("x", { status: 200, headers: { "content-type": "image/png" } }) });
  assert.deepEqual(await fetchPageMetadata("https://example.com/img", d), { title: null, imageUrl: null });
  assert.deepEqual(await fetchPageMetadata("https://example.com/missing", d), { title: null, imageUrl: null });
});

test("private ranges", () => {
  for (const a of ["127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:10.0.0.1"]) {
    assert.equal(isPrivateAddress(a), true, a);
  }
  for (const a of ["93.184.216.34", "8.8.8.8", "172.32.0.1", "2606:4700::1111"]) assert.equal(isPrivateAddress(a), false, a);
});
