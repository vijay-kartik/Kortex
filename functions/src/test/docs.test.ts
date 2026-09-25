import assert from "node:assert/strict";
import { test } from "node:test";
import { isItemFilePath, itemDoc, parseNewItem } from "../docs";
import { Input } from "../input";
import { parseWebAddress } from "../webAddress";

const parse = (item: unknown) => parseNewItem(Input.of(item, "item"));

test("web addresses get https when they have no scheme", () => {
  assert.equal(parseWebAddress(" example.com/post "), "https://example.com/post");
  assert.equal(parseWebAddress("http://example.com"), "http://example.com");
  assert.equal(parseWebAddress("not a url"), null);
  assert.equal(parseWebAddress("ftp://example.com"), null);
  assert.equal(parseWebAddress("hello"), null);
});

test("a note is trimmed and can't be blank", () => {
  assert.deepEqual(parse({ type: "Note", text: "  hi  " }).fields, { text: "hi" });
  assert.throws(() => parse({ type: "Note", text: "   " }), /item.text is required/);
});

test("link types carry their address and only articles and videos are done", () => {
  const link = parse({ type: "Link", url: "example.com", title: " Example ", done: true });
  assert.deepEqual(link.link, { url: "https://example.com", title: "Example" });
  assert.deepEqual(link.fields, {});

  const article = parse({ type: "Article", url: "https://example.com/a", readingMinutes: 4, done: true });
  assert.deepEqual(article.fields, { done: true, readingMinutes: 4 });

  const video = parse({ type: "Video", url: "https://youtu.be/x", durationSeconds: 90 });
  assert.deepEqual(video.fields, { done: false, durationSeconds: 90 });

  assert.throws(() => parse({ type: "Link", url: "not a url" }), /not a web address/);
});

test("docs and images need a file; a bill's is optional", () => {
  assert.throws(() => parse({ type: "Doc", title: "Lease" }), /item.file is required/);
  const doc = parse({ type: "Doc", title: "Lease", pageCount: 3, file: { storagePath: "p" } });
  assert.deepEqual(doc.fields, { title: "Lease", pageCount: 3 });
  assert.equal(doc.fileRequired, true);

  const image = parse({ type: "Image", caption: " view ", file: { storagePath: "p", mimeType: "image/png" } });
  assert.deepEqual(image.fields, { text: "view" });

  const bill = parse({ type: "Bill", title: "Power", amountMinor: 123450, currency: "inr", dueAt: 1_700_000_000_000, paid: true });
  assert.deepEqual(bill.fields, { title: "Power", amountMinor: 123450, currency: "INR", done: true, dueAt: 1_700_000_000_000 });
  assert.equal(bill.file, null);
  assert.equal(bill.fileRequired, false);

  assert.throws(() => parse({ type: "Bill", title: "Power", amountMinor: 1.5, currency: "INR" }), /amountMinor/);
  assert.throws(() => parse({ type: "Bill", title: "Power", amountMinor: 1, currency: "RS" }), /ISO 4217/);
});

test("an email is stored as the app stores one", () => {
  const email = parse({ type: "Email", messageId: "m1", subject: "Hi", from: "Ada <ada@example.com>", sentAt: 5 });
  assert.deepEqual(email.fields, { messageId: "m1", title: "Hi", text: "", fromAddress: "Ada <ada@example.com>", sentAt: 5 });
  assert.throws(() => parse({ type: "Email", subject: "Hi" }), /messageId is required/);
});

test("unknown types are rejected", () => {
  assert.throws(() => parse({ type: "Podcast" }), /item.type must be one of/);
});

test("an item's file must sit at its own path", () => {
  const item = "0f8fad5b-d9cb-469f-a165-70867728950e";
  assert.ok(isItemFilePath(`users/u1/topic-files/${item}.pdf`, "u1", item));
  assert.ok(isItemFilePath(`users/u1/topic-files/${item}`, "u1", item));
  assert.ok(!isItemFilePath(`users/u2/topic-files/${item}.pdf`, "u1", item));
  assert.ok(!isItemFilePath(`users/u1/topic-files/${item}/../x.pdf`, "u1", item));
});

test("an item document has the sync fields the app reads", () => {
  const doc = itemDoc({
    topicUid: "t1",
    type: "Article",
    fields: { done: true },
    linkUid: "l1",
    file: null,
    pinned: false,
    nowMillis: 10,
    serverTime: "server",
  });
  assert.deepEqual(doc, {
    topicUid: "t1",
    type: "Article",
    addedAt: 10,
    done: true,
    pinned: false,
    updatedAt: 10,
    serverUpdatedAt: "server",
    deleted: false,
    linkUid: "l1",
  });
});
