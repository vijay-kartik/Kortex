/**
 * Firestore documents as the app's sync reads them (docs/CLOUD_SYNC_PLAN.md › Firestore layout).
 * Field names are the contract with `:sync` (`LinkDocs.kt`, `TopicSyncDao.kt`); renaming one
 * here breaks the app's pull.
 */

import { Input, invalid } from "./input";
import { parseWebAddress } from "./webAddress";

export type Doc = Record<string, unknown>;

export const MAX_TAGS = 50;
export const MAX_TAG_LENGTH = 100;
export const MAX_TITLE = 1_000;
export const MAX_URL = 4_000;

export function linkDoc(link: {
  url: string;
  urlKey: string;
  title: string;
  tags: string[];
  imageUrl: string | null;
  nowMillis: number;
  serverTime: unknown;
}): Doc {
  return {
    url: link.url,
    urlKey: link.urlKey,
    title: link.title,
    tags: link.tags,
    imageUrl: link.imageUrl,
    imageHidden: false,
    createdAt: link.nowMillis,
    updatedAt: link.nowMillis,
    serverUpdatedAt: link.serverTime,
    deleted: false,
  };
}

export function topicDoc(topic: { name: string; purpose: string | null; pinned: boolean; nowMillis: number; serverTime: unknown }): Doc {
  return {
    name: topic.name,
    purpose: topic.purpose,
    pinned: topic.pinned,
    // Topics are no longer given sections; theirs come from what they hold (Topic.kt).
    sections: [],
    createdAt: topic.nowMillis,
    updatedAt: topic.nowMillis,
    serverUpdatedAt: topic.serverTime,
    deleted: false,
  };
}

/** What adding an item changes on its topic: the app's `TopicDao.addItem` touches it the same way. */
export function touchedTopic(nowMillis: number, serverTime: unknown): Doc {
  return { updatedAt: nowMillis, serverUpdatedAt: serverTime };
}

/** `ItemType` in topics/…/ItemType.kt. */
export const ITEM_TYPES = ["Note", "Link", "Article", "Video", "Doc", "Image", "Bill", "Email"] as const;
export type ItemType = (typeof ITEM_TYPES)[number];

/** A file the caller already uploaded to Storage for the item. */
export interface FileRef {
  storagePath: string;
  mimeType: string | null;
  name: string | null;
}

/** An item as a caller submits it, checked and trimmed, before its link or file is resolved. */
export interface NewItem {
  type: ItemType;
  /** Every field of the item's document that comes straight from the request. */
  fields: Doc;
  /** Link, Article and Video: the address, saved to the Links library too. */
  link: { url: string; title: string | null } | null;
  file: FileRef | null;
  /** Doc and Image need a file; a Bill's invoice is optional. */
  fileRequired: boolean;
}

/**
 * Reads `item` from an `addTopicItem` request by the rules of the app's `AddItem`: a blank note
 * or title, or a link that isn't a web address, is invalid.
 */
export function parseNewItem(item: Input): NewItem {
  const type = item.string("type", 20) as ItemType;
  if (!ITEM_TYPES.includes(type)) throw invalid(`item.type must be one of ${ITEM_TYPES.join(", ")}.`);

  const base = { type, fields: {} as Doc, link: null, file: null, fileRequired: false };
  switch (type) {
    case "Note":
      return { ...base, fields: { text: item.string("text") } };

    case "Link":
    case "Article":
    case "Video": {
      const raw = item.string("url", MAX_URL);
      const url = parseWebAddress(raw);
      if (url === null) throw invalid("item.url is not a web address.");
      const fields: Doc = {};
      // An article is done once read, a video once watched; a plain link never is.
      if (type !== "Link") fields.done = item.boolean("done", false);
      if (type === "Article") setIfPresent(fields, "readingMinutes", item.optionalInt("readingMinutes", 0, 10_000));
      if (type === "Video") setIfPresent(fields, "durationSeconds", item.optionalInt("durationSeconds", 0, 1_000_000));
      return { ...base, fields, link: { url, title: item.optionalString("title", MAX_TITLE) } };
    }

    case "Doc": {
      const fields: Doc = { title: item.string("title", MAX_TITLE) };
      setIfPresent(fields, "pageCount", item.optionalInt("pageCount", 0, 100_000));
      return { ...base, fields, file: parseFile(item.object("file")), fileRequired: true };
    }

    case "Image": {
      const fields: Doc = {};
      setIfPresent(fields, "text", item.optionalString("caption"));
      return { ...base, fields, file: parseFile(item.object("file")), fileRequired: true };
    }

    case "Bill": {
      const currency = item.string("currency", 3).toUpperCase();
      if (!/^[A-Z]{3}$/.test(currency)) throw invalid("item.currency must be an ISO 4217 code, such as INR.");
      const fields: Doc = {
        title: item.string("title", MAX_TITLE),
        amountMinor: item.int("amountMinor", 0),
        currency,
        done: item.boolean("paid", false),
      };
      setIfPresent(fields, "issuedAt", item.optionalMillis("issuedAt"));
      setIfPresent(fields, "dueAt", item.optionalMillis("dueAt"));
      const file = item.optionalObject("file");
      return { ...base, fields, file: file && parseFile(file) };
    }

    case "Email": {
      // The mailbox's fields, as the app's `toEntity` stores them: subject as title, snippet as text.
      const fields: Doc = {
        messageId: item.string("messageId", 500),
        title: item.optionalString("subject", MAX_TITLE) ?? "",
        text: item.optionalString("snippet") ?? "",
        fromAddress: item.optionalString("from", MAX_TITLE) ?? "",
      };
      setIfPresent(fields, "threadId", item.optionalString("threadId", 500));
      setIfPresent(fields, "rfc822MessageId", item.optionalString("rfc822MessageId", 1_000));
      setIfPresent(fields, "accountEmail", item.optionalString("accountEmail", 320));
      setIfPresent(fields, "sentAt", item.optionalMillis("sentAt"));
      return { ...base, fields };
    }
  }
}

export function itemDoc(item: {
  topicUid: string;
  type: ItemType;
  fields: Doc;
  linkUid: string | null;
  file: { storagePath: string; mimeType: string; name: string } | null;
  pinned: boolean;
  nowMillis: number;
  serverTime: unknown;
}): Doc {
  const doc: Doc = {
    topicUid: item.topicUid,
    type: item.type,
    addedAt: item.nowMillis,
    done: false,
    ...item.fields,
    pinned: item.pinned,
    updatedAt: item.nowMillis,
    serverUpdatedAt: item.serverTime,
    deleted: false,
  };
  if (item.linkUid !== null) doc.linkUid = item.linkUid;
  if (item.file !== null) doc.file = item.file;
  return doc;
}

/** Where an item's file must sit: `users/{uid}/topic-files/{itemUid}{ext}` (storage.rules). */
export function isItemFilePath(storagePath: string, userUid: string, itemUid: string): boolean {
  const prefix = `users/${userUid}/topic-files/${itemUid}`;
  return storagePath.startsWith(prefix) && /^(\.[A-Za-z0-9]{1,10})?$/.test(storagePath.slice(prefix.length));
}

function parseFile(file: Input): FileRef {
  return {
    storagePath: file.string("storagePath", 1_000),
    mimeType: file.optionalString("mimeType", 200),
    name: file.optionalString("name", 500),
  };
}

function setIfPresent(doc: Doc, key: string, value: unknown): void {
  if (value !== null && value !== undefined) doc[key] = value;
}
