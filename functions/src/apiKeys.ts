/**
 * Personal API keys, so a script, shortcut or other device can call the functions through `api`
 * without a Firebase sign-in. A key stands in for its user; they can revoke it at any time.
 *
 * Only a key's SHA-256 is stored, as the id of `apiKeys/{keyId}`, so reading the database never
 * reveals a usable key. That collection sits outside `users/{uid}`, so the security rules keep
 * every client out of it; only these functions (the Admin SDK) touch it.
 */

import { createHash, randomBytes } from "node:crypto";
import { FieldValue, getFirestore, Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { Input } from "./input";

export const API_KEY_PREFIX = "kx_";
const MAX_KEYS_PER_USER = 10;
const MAX_LABEL = 100;
/** `lastUsedAt` is only rewritten this often, so a busy key doesn't cost a write per call. */
const LAST_USED_RESOLUTION_MS = 60 * 60 * 1000;

/** `kx_` and 32 random bytes, base64url: 256 bits, so keys can't be guessed. */
export function newApiKey(): string {
  return API_KEY_PREFIX + randomBytes(32).toString("base64url");
}

/** The key's document id: its SHA-256 in hex. */
export function apiKeyId(key: string): string {
  return createHash("sha256").update(key, "utf8").digest("hex");
}

/** The key in an `Authorization: Bearer kx_…` header; null when there is none. */
export function bearerKey(header: string | undefined): string | null {
  const match = /^Bearer\s+(\S+)\s*$/i.exec(header ?? "");
  return match && match[1].startsWith(API_KEY_PREFIX) ? match[1] : null;
}

/**
 * Makes a key for [userUid]. The key itself is in the response only: it can't be shown again,
 * and a lost key is revoked and replaced.
 *
 * Request: `{ label? }`, to tell keys apart ("iPhone shortcut"). Response: `{ key, keyId, label, hint }`.
 */
export async function createApiKey(userUid: string, data: unknown) {
  const label = Input.of(data ?? {}).optionalString("label", MAX_LABEL);
  const keys = getFirestore().collection("apiKeys");
  const owned = await keys.where("uid", "==", userUid).count().get();
  if (owned.data().count >= MAX_KEYS_PER_USER) {
    throw new HttpsError("resource-exhausted", `You can have ${MAX_KEYS_PER_USER} API keys. Revoke one first.`);
  }

  const key = newApiKey();
  const keyId = apiKeyId(key);
  const hint = key.slice(-4);
  await keys.doc(keyId).create({ uid: userUid, label, hint, createdAt: FieldValue.serverTimestamp(), lastUsedAt: null });
  return { key, keyId, label, hint };
}

/** Response: `{ keys: [{ keyId, label, hint, createdAt, lastUsedAt }] }`, times in epoch millis. */
export async function listApiKeys(userUid: string) {
  const snapshot = await getFirestore().collection("apiKeys").where("uid", "==", userUid).get();
  const keys = snapshot.docs.map((doc) => ({
    keyId: doc.id,
    label: doc.get("label") ?? null,
    hint: doc.get("hint") ?? null,
    createdAt: millis(doc.get("createdAt")),
    lastUsedAt: millis(doc.get("lastUsedAt")),
  }));
  keys.sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0));
  return { keys };
}

/** Request: `{ keyId }`. The key stops working at once. Response: `{ revoked: true }`. */
export async function revokeApiKey(userUid: string, data: unknown) {
  const keyId = Input.of(data).string("keyId", 64);
  const ref = getFirestore().collection("apiKeys").doc(keyId);
  await getFirestore().runTransaction(async (tx) => {
    const snapshot = await tx.get(ref);
    // Someone else's key reads as missing, so ids can't be probed.
    if (!snapshot.exists || snapshot.get("uid") !== userUid) throw new HttpsError("not-found", "No such API key.");
    tx.delete(ref);
  });
  return { revoked: true };
}

/** The uid a key belongs to; `unauthenticated` for an unknown or revoked key. */
export async function userForApiKey(key: string): Promise<string> {
  const ref = getFirestore().collection("apiKeys").doc(apiKeyId(key));
  const snapshot = await ref.get();
  const uid = snapshot.get("uid");
  if (!snapshot.exists || typeof uid !== "string") throw new HttpsError("unauthenticated", "That API key isn't valid. It may have been revoked.");

  const lastUsed = millis(snapshot.get("lastUsedAt"));
  if (lastUsed === null || Date.now() - lastUsed > LAST_USED_RESOLUTION_MS) {
    // Bookkeeping only: a failure here mustn't fail the call.
    ref.update({ lastUsedAt: FieldValue.serverTimestamp() }).catch(() => undefined);
  }
  return uid;
}

function millis(value: unknown): number | null {
  return value instanceof Timestamp ? value.toMillis() : null;
}
