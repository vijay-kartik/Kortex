/**
 * What the functions do, apart from how the caller signed in: each takes the user's uid and the
 * request's untrusted data. `index.ts` exposes them as callables (Firebase sign-in) and through
 * `api` (a personal API key).
 *
 * They write the same documents the app's sync pushes (docs/CLOUD_SYNC_PLAN.md › Firestore
 * layout) and only touch `users/{uid}/**` of that user.
 */

import { randomUUID } from "node:crypto";
import { DocumentReference, FieldValue, getFirestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { itemDoc, linkDoc, MAX_TAG_LENGTH, MAX_TAGS, MAX_TITLE, MAX_URL, parseNewItem, topicDoc, touchedTopic } from "./docs";
import { Input, invalid } from "./input";
import { linkUid, linkUrlKey } from "./linkKey";
import { parseWebAddress } from "./webAddress";

export type Action = (userUid: string, data: unknown) => Promise<unknown>;

/**
 * Saves a link to the Links library.
 *
 * Request: `{ url, title?, tags?: string[], imageUrl? }`. A url without a scheme gets `https://`;
 * a missing title falls back to the url, as the app's pull does.
 * Response: `{ linkUid, created }`. The document id comes from the address, so a page the user
 * already saved isn't saved twice: `created` is false and the saved link is left as it is.
 */
export async function addLink(userUid: string, data: unknown) {
  const input = Input.of(data);
  const url = webAddress(input.string("url", MAX_URL), "url");
  const title = input.optionalString("title", MAX_TITLE) ?? url;
  const tags = input.stringList("tags", MAX_TAGS, MAX_TAG_LENGTH);
  const rawImageUrl = input.optionalString("imageUrl", MAX_URL);
  const imageUrl = rawImageUrl && webAddress(rawImageUrl, "imageUrl");

  const urlKey = linkUrlKey(url);
  const uid = linkUid(urlKey);
  const ref = userDoc(userUid).collection("links").doc(uid);

  return getFirestore().runTransaction(async (tx) => {
    if (isLive(await tx.get(ref))) return { linkUid: uid, created: false };
    // A deleted doc is overwritten whole: saving the page again starts it afresh, as in the app.
    tx.set(ref, linkDoc({ url, urlKey, title, tags, imageUrl, nowMillis: Date.now(), serverTime: FieldValue.serverTimestamp() }));
    return { linkUid: uid, created: true };
  });
}

/**
 * Creates a topic.
 *
 * Request: `{ name, purpose?, pinned? }`. Names are trimmed and unique ignoring case, as in the
 * app's `CreateTopic`.
 * Response: `{ topicUid }`. Throws `already-exists` when another topic has the name.
 */
export async function createTopic(userUid: string, data: unknown) {
  const input = Input.of(data);
  const name = input.string("name", MAX_TOPIC_NAME);
  const purpose = input.optionalString("purpose", MAX_PURPOSE);
  const pinned = input.boolean("pinned", false);

  const topics = userDoc(userUid).collection("topics");
  return getFirestore().runTransaction(async (tx) => {
    const live = await tx.get(topics.where("deleted", "==", false).select("name"));
    const taken = live.docs.some((doc) => sameName(doc.get("name"), name));
    if (taken) throw new HttpsError("already-exists", `A topic named "${name}" already exists.`);

    const ref = topics.doc(randomUUID());
    tx.create(ref, topicDoc({ name, purpose, pinned, nowMillis: Date.now(), serverTime: FieldValue.serverTimestamp() }));
    return { topicUid: ref.id };
  });
}

/**
 * Adds an item to a topic: a Note, Link, Article, Video, Bill or Email.
 *
 * Request: `{ topicUid | topicName, item: { type, ... }, pinned? }`. `topicName` matches ignoring
 * case, for callers that only know what the user sees (a shortcut's topic picker). `item` carries:
 * - Note: `text`
 * - Link / Article / Video: `url`, `title?`; Article `readingMinutes?`, `done?` (read);
 *   Video `durationSeconds?`, `done?` (watched). The link is saved to the Links library too,
 *   unless it is there already.
 * - Bill: `title`, `amountMinor` (paise, cents…), `currency` (ISO 4217), `issuedAt?`, `dueAt?`
 *   (epoch millis), `paid?`
 * - Email: `messageId`, `threadId?`, `subject?`, `from?`, `snippet?`, `sentAt?`,
 *   `rfc822MessageId?`, `accountEmail?`
 *
 * Doc and Image items, and a bill's invoice, are refused with `failed-precondition`: they need
 * files, which wait on Cloud Storage.
 *
 * Response: `{ itemUid, topicUid, linkUid? }`. Throws `not-found` for a missing or deleted topic and
 * `already-exists` when the topic already holds the link.
 */
export async function addTopicItem(userUid: string, data: unknown) {
  const input = Input.of(data);
  const givenUid = input.optionalString("topicUid", 128);
  const givenName = givenUid === null ? input.optionalString("topicName", MAX_TOPIC_NAME) : null;
  if (givenUid === null && givenName === null) throw invalid("topicUid or topicName is required.");
  const pinned = input.boolean("pinned", false);
  const item = parseNewItem(input.object("item"));

  const user = userDoc(userUid);
  const topics = user.collection("topics");
  const itemRef = user.collection("topicItems").doc(randomUUID());

  return getFirestore().runTransaction(async (tx) => {
    // Every read comes before the first write, as a transaction requires.
    let topicRef: DocumentReference;
    if (givenUid !== null) {
      topicRef = topics.doc(givenUid);
      if (!isLive(await tx.get(topicRef))) throw new HttpsError("not-found", "That topic doesn't exist.");
    } else {
      const live = await tx.get(topics.where("deleted", "==", false).select("name"));
      const match = live.docs.find((doc) => sameName(doc.get("name"), givenName!));
      if (!match) throw new HttpsError("not-found", `No topic is named "${givenName}".`);
      topicRef = match.ref;
    }
    const topicUid = topicRef.id;

    const nowMillis = Date.now();
    const serverTime = FieldValue.serverTimestamp();
    let uid: string | null = null;
    let newLink: { ref: DocumentReference; doc: Record<string, unknown> } | null = null;
    if (item.link !== null) {
      const { url, title } = item.link;
      const urlKey = linkUrlKey(url);
      uid = linkUid(urlKey);
      const linkRef = user.collection("links").doc(uid);
      const held = await tx.get(
        user.collection("topicItems")
          .where("topicUid", "==", topicUid)
          .where("linkUid", "==", uid)
          .where("deleted", "==", false)
          .limit(1),
      );
      if (!held.empty) throw new HttpsError("already-exists", "The topic already holds this link.");
      // The item points at the link, so the app can only show it once the link is in the library.
      if (!isLive(await tx.get(linkRef))) {
        newLink = { ref: linkRef, doc: linkDoc({ url, urlKey, title: title ?? url, tags: [], imageUrl: null, nowMillis, serverTime }) };
      }
    }

    if (newLink !== null) tx.set(newLink.ref, newLink.doc);
    tx.create(itemRef, itemDoc({ topicUid, type: item.type, fields: item.fields, linkUid: uid, pinned, nowMillis, serverTime }));
    tx.update(topicRef, touchedTopic(nowMillis, serverTime));
    return uid === null ? { itemUid: itemRef.id, topicUid } : { itemUid: itemRef.id, topicUid, linkUid: uid };
  });
}

/**
 * The user's topics, pinned first, then by name.
 *
 * Request: nothing. Response: `{ topics: [{ topicUid, name, pinned }], names: [name] }`; `names` is
 * the same order, ready for a picker such as a shortcut's "Choose from List".
 */
export async function listTopics(userUid: string) {
  const live = await userDoc(userUid).collection("topics").where("deleted", "==", false).select("name", "pinned").get();
  const topics = live.docs
    .map((doc) => ({ topicUid: doc.id, name: String(doc.get("name") ?? ""), pinned: doc.get("pinned") === true }))
    .filter((topic) => topic.name.length > 0)
    .sort((a, b) => Number(b.pinned) - Number(a.pinned) || a.name.localeCompare(b.name, undefined, { sensitivity: "base" }));
  return { topics, names: topics.map((topic) => topic.name) };
}

function userDoc(userUid: string): DocumentReference {
  return getFirestore().collection("users").doc(userUid);
}

/** Exists and isn't soft-deleted. */
function isLive(snapshot: FirebaseFirestore.DocumentSnapshot): boolean {
  return snapshot.exists && snapshot.get("deleted") !== true;
}

/** Topic names are unique ignoring case, as in the app. */
function sameName(stored: unknown, name: string): boolean {
  return String(stored ?? "").toLowerCase() === name.toLowerCase();
}

function webAddress(text: string, field: string): string {
  const url = parseWebAddress(text);
  if (url === null) throw invalid(`${field} is not a web address.`);
  return url;
}

const MAX_TOPIC_NAME = 200;
const MAX_PURPOSE = 2_000;
