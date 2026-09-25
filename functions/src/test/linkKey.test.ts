import assert from "node:assert/strict";
import { test } from "node:test";
import { linkUid, linkUrlKey } from "../linkKey";

// The same vectors as links/src/test/…/LinkUrlKeyTest.kt and LinkUidTest.kt: the app's contract.

test("re-shares of one page share a key", () => {
  const key = linkUrlKey("https://example.com/post");
  for (const url of [
    "https://www.Example.com/post/",
    "http://example.com/post",
    "https://example.com/post#comments",
    "https://example.com/post?utm_source=x&utm_medium=social",
    "https://example.com/post?fbclid=abc",
    "https://example.com:443/post",
    "  https://example.com/post  ",
  ]) {
    assert.equal(linkUrlKey(url), key, url);
  }
});

test("meaningful differences keep keys apart", () => {
  const key = linkUrlKey("https://example.com/post");
  for (const url of [
    "https://example.com/post?id=2",
    "https://example.com/Post",
    "https://example.com/post/2",
    "https://example.com:8080/post",
    "https://blog.example.com/post",
  ]) {
    assert.notEqual(linkUrlKey(url), key, url);
  }
});

test("tracking params are dropped but others keep their order", () => {
  assert.equal(linkUrlKey("https://example.com/search?utm_campaign=x&q=room&gclid=y&page=2"), "example.com/search?q=room&page=2");
});

test("root path and bare host match", () => {
  assert.equal(linkUrlKey("https://example.com/"), linkUrlKey("https://example.com"));
});

test("non-web input is only trimmed", () => {
  assert.equal(linkUrlKey(" not a url "), "not a url");
  assert.equal(linkUrlKey("mailto:a@b.com"), "mailto:a@b.com");
});

test("uid is the first 32 hex chars of SHA-256 over the url key", () => {
  const vectors: Record<string, string> = {
    "example.com/post": "1d990bb353ea3e44e1e1b66dc6c0b41f",
    "curaahome.com/products/curaa-automatic-pepper-grinder": "6b8f106dce806fa11f62324e43644b49",
    "google.com": "d4c9d9027326271a89ce51fcaf328ed6",
    "news.ycombinator.com/item?id=1": "92237aeac6ebf5b11122597e3239e3dc",
    "münchen.de/straße": "366d475401ab8f89b62848fb9221bd5d",
    "not a url": "d8b5bf9b9fd4760c61234d12614d80c9",
  };
  for (const [urlKey, uid] of Object.entries(vectors)) assert.equal(linkUid(urlKey), uid, urlKey);
});

test("re-shares of one page get one uid", () => {
  const uid = linkUid(linkUrlKey("https://example.com/post"));
  for (const url of ["https://www.Example.com/post/", "http://example.com/post?utm_source=x", "https://example.com/post#comments"]) {
    assert.equal(linkUid(linkUrlKey(url)), uid, url);
  }
  assert.equal(linkUid(linkUrlKey("https://münchen.de/straße")), "366d475401ab8f89b62848fb9221bd5d");
});
