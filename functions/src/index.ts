/**
 * Callable functions that write a user's Links and Topics straight into Firestore, for clients
 * that don't hold the app's database (the browser extension, shortcuts, the agent). They write the
 * same documents the app's sync pushes (docs/CLOUD_SYNC_PLAN.md › Firestore layout), and the app
 * picks them up live, or on its next sync.
 *
 * All three need a signed-in Firebase user and only touch `users/{uid}/**` of that user.
 */

import { randomUUID } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { DocumentReference, FieldValue, getFirestore } from "firebase-admin/firestore";
import { setGlobalOptions } from "firebase-functions/v2";
import { CallableRequest, HttpsError, onCall } from "firebase-functions/v2/https";
import { itemDoc, linkDoc, MAX_TAG_LENGTH, MAX_TAGS, MAX_TITLE, MAX_URL, parseNewItem, topicDoc, touchedTopic } from "./docs";
import { Input, invalid } from "./input";
import { linkUid, linkUrlKey } from "./linkKey";
import { parseWebAddress } from "./webAddress";

initializeApp();
// Next to Firestore (asia-south1), which can't move.
setGlobalOptions({ region: "asia-south1", maxInstances: 10 });

const db = getFirestore();

/**
 * Saves a link to the Links library.
 *
 * Request: `{ url, title?, tags?: string[], imageUrl? }`. A url without a scheme gets `https://`;
 * a missing title falls back to the url, as the app's pull does.
 * Response: `{ linkUid, created }`. The document id comes from the address, so a page the user
 * already saved isn't saved twice: `created` is false and the saved link is left as it is.
 */
export const addLink = onCall(async (request) => {
  const userUid = signedInUser(request);
  const input = Input.of(request.data);
  const url = webAddress(input.string("url", MAX_URL), "url");
  const title = input.optionalString("title", MAX_TITLE) ?? url;
  const tags = input.stringList("tags", MAX_TAGS, MAX_TAG_LENGTH);
  const rawImageUrl = input.optionalString("imageUrl", MAX_URL);
  const imageUrl = rawImageUrl && webAddress(rawImageUrl, "imageUrl");

  const urlKey = linkUrlKey(url);
  const uid = linkUid(urlKey);
  const ref = userDoc(userUid).collection("links").doc(uid);

  return db.runTransaction(async (tx) => {
    if (isLive(await tx.get(ref))) return { linkUid: uid, created: false };
    // A deleted doc is overwritten whole: saving the page again starts it afresh, as in the app.
    tx.set(ref, linkDoc({ url, urlKey, title, tags, imageUrl, nowMillis: Date.now(), serverTime: FieldValue.serverTimestamp() }));
    return { linkUid: uid, created: true };
  });
});

/**
 * Creates a topic.
 *
 * Request: `{ name, purpose?, pinned? }`. Names are trimmed and unique ignoring case, as in the
 * app's `CreateTopic`.
 * Response: `{ topicUid }`. Throws `already-exists` when another topic has the name.
 */
export const createTopic = onCall(async (request) => {
  const userUid = signedInUser(request);
  const input = Input.of(request.data);
  const name = input.string("name", MAX_TOPIC_NAME);
  const purpose = input.optionalString("purpose", MAX_PURPOSE);
  const pinned = input.boolean("pinned", false);

  const topics = userDoc(userUid).collection("topics");
  return db.runTransaction(async (tx) => {
    const live = await tx.get(topics.where("deleted", "==", false).select("name"));
    const taken = live.docs.some((doc) => String(doc.get("name") ?? "").toLowerCase() === name.toLowerCase());
    if (taken) throw new HttpsError("already-exists", `A topic named "${name}" already exists.`);

    const ref = topics.doc(randomUUID());
    tx.create(ref, topicDoc({ name, purpose, pinned, nowMillis: Date.now(), serverTime: FieldValue.serverTimestamp() }));
    return { topicUid: ref.id };
  });
});

/**
 * Adds an item to a topic: a Note, Link, Article, Video, Bill or Email.
 *
 * Request: `{ topicUid, item: { type, ... }, pinned? }`, where `item` carries:
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
 * Response: `{ itemUid, linkUid? }`. Throws `not-found` for a missing or deleted topic and
 * `already-exists` when the topic already holds the link.
 */
export const addTopicItem = onCall(async (request) => {
  const userUid = signedInUser(request);
  const input = Input.of(request.data);
  const topicUid = input.string("topicUid", 128);
  const pinned = input.boolean("pinned", false);
  const item = parseNewItem(input.object("item"));

  const user = userDoc(userUid);
  const topicRef = user.collection("topics").doc(topicUid);
  const itemRef = user.collection("topicItems").doc(randomUUID());

  return db.runTransaction(async (tx) => {
    // Every read comes before the first write, as a transaction requires.
    if (!isLive(await tx.get(topicRef))) throw new HttpsError("not-found", "That topic doesn't exist.");

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
    return uid === null ? { itemUid: itemRef.id } : { itemUid: itemRef.id, linkUid: uid };
  });
});

function signedInUser(request: CallableRequest): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to Kortex first.");
  return uid;
}

function userDoc(userUid: string): DocumentReference {
  return db.collection("users").doc(userUid);
}

/** Exists and isn't soft-deleted. */
function isLive(snapshot: FirebaseFirestore.DocumentSnapshot): boolean {
  return snapshot.exists && snapshot.get("deleted") !== true;
}

function webAddress(text: string, field: string): string {
  const url = parseWebAddress(text);
  if (url === null) throw invalid(`${field} is not a web address.`);
  return url;
}

const MAX_TOPIC_NAME = 200;
const MAX_PURPOSE = 2_000;
