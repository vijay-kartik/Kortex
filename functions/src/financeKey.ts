/**
 * The data key that encrypts a user's full card and account numbers on their phones
 * (docs/FINANCE_PLAN.md › Decisions). One 256-bit key per user, made here the first time it's asked
 * for and stored only wrapped by Cloud KMS in `financeKeys/{uid}` — outside `users/{uid}`, so the
 * security rules keep every client out and only this function reads it.
 *
 * Only a signed-in user calling from the Kortex app gets it (App Check, enforced in `index.ts`).
 * The phone keeps it wrapped by its own Keystore; the synced `finSecrets` hold cipher text only.
 */

import { randomBytes } from "node:crypto";
import { KeyManagementServiceClient } from "@google-cloud/kms";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { defineString } from "firebase-functions/params";

/**
 * The KMS key that wraps every data key:
 * `projects/{project}/locations/asia-south1/keyRings/kortex/cryptoKeys/finance-keys`. Set at deploy
 * (`.env` or the deploy prompt); the functions' service account needs Encrypter/Decrypter on it.
 */
export const FINANCE_KMS_KEY = defineString("FINANCE_KMS_KEY", {
  description: "Cloud KMS key that wraps users' finance data keys (projects/…/cryptoKeys/…)",
});

export const KEY_VERSION = 1;

let kms: KeyManagementServiceClient | null = null;

/** Response: `{ key: base64 of 32 bytes, keyVersion }`. */
export async function financeKey(userUid: string, _data: unknown) {
  const ref = getFirestore().collection("financeKeys").doc(userUid);
  const existing = await ref.get();
  if (existing.exists) return { key: (await unwrap(userUid, String(existing.get("wrappedKey")))).toString("base64"), keyVersion: existing.get("keyVersion") ?? KEY_VERSION };

  const key = randomBytes(32);
  try {
    await ref.create({ wrappedKey: await wrap(userUid, key), keyVersion: KEY_VERSION, createdAt: FieldValue.serverTimestamp() });
  } catch (e) {
    // Two phones asked at once and the other one won: use its key, so both seal with the same one.
    const winner = await ref.get();
    if (!winner.exists) throw e;
    return { key: (await unwrap(userUid, String(winner.get("wrappedKey")))).toString("base64"), keyVersion: winner.get("keyVersion") ?? KEY_VERSION };
  }
  return { key: key.toString("base64"), keyVersion: KEY_VERSION };
}

/** The user's uid is bound in as associated data, so one user's wrapped key can't be unwrapped as another's. */
function aad(userUid: string): Buffer {
  return Buffer.from(`kortex.financeKey:${userUid}`, "utf8");
}

async function wrap(userUid: string, key: Buffer): Promise<string> {
  const [result] = await client().encrypt({ name: FINANCE_KMS_KEY.value(), plaintext: key, additionalAuthenticatedData: aad(userUid) });
  return Buffer.from(result.ciphertext as Uint8Array).toString("base64");
}

async function unwrap(userUid: string, wrapped: string): Promise<Buffer> {
  const [result] = await client().decrypt({
    name: FINANCE_KMS_KEY.value(),
    ciphertext: Buffer.from(wrapped, "base64"),
    additionalAuthenticatedData: aad(userUid),
  });
  return Buffer.from(result.plaintext as Uint8Array);
}

function client(): KeyManagementServiceClient {
  kms ??= new KeyManagementServiceClient();
  return kms;
}
