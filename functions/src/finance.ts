/**
 * Finance actions for the extension, scripts and the API (docs/FINANCE_PLAN.md › Cloud Functions).
 * They write the documents the app's sync pulls live; the phone recomputes every balance from them.
 */

import { randomUUID } from "node:crypto";
import { DocumentReference, FieldValue, getFirestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import {
  BUILT_IN_CATEGORIES, dayIn, FinAccount, FinCategory, merchantDoc, merchantUid, parseNewTransaction, resolveAccount,
  resolveCategory, transactionDoc,
} from "./financeDocs";

/**
 * Adds an expense or income.
 *
 * Request: `{ type: "EXPENSE" | "INCOME", amountMinor, currency?, occurredAt?, timeZone?, account,
 * category?, merchant?, note? }`. `account` is the account's uid, last 4 digits or name;
 * `category` a uid or name of the matching kind. The day is taken in `timeZone` (default
 * Asia/Kolkata), as the phone takes it in its own.
 * Response: `{ txUid, accountUid, categoryUid }`: what the names matched. Throws `not-found` for no matching account or category,
 * `failed-precondition` when several accounts match or income would go into a credit card.
 */
export async function addTransaction(userUid: string, data: unknown) {
  const nowMillis = Date.now();
  const entry = parseNewTransaction(data, nowMillis);
  const user = userDoc(userUid);
  const txRef = user.collection("finTransactions").doc(randomUUID());

  return getFirestore().runTransaction(async (tx) => {
    const accounts = (await tx.get(user.collection("finAccounts").where("deleted", "==", false))).docs
      .filter((doc) => doc.get("archived") !== true)
      .map((doc): FinAccount => ({ uid: doc.id, name: String(doc.get("name") ?? ""), kind: String(doc.get("kind") ?? ""), last4: doc.get("last4") ?? null }));
    const account = resolveAccount(accounts, entry.account);
    if (entry.type === "INCOME" && account.kind === "CREDIT_CARD") {
      throw new HttpsError("failed-precondition", "Income can't go into a credit card: refunds aren't handled yet.");
    }
    const userCategories = (await tx.get(user.collection("finCategories").where("deleted", "==", false))).docs.map(toCategory);
    const category = resolveCategory([...BUILT_IN_CATEGORIES, ...userCategories], entry.category, entry.type);
    const merchantRef = entry.merchant === null ? null : user.collection("finMerchants").doc(merchantUid(entry.merchant));
    const known = merchantRef === null ? null : await tx.get(merchantRef);

    const serverTime = FieldValue.serverTimestamp();
    tx.create(txRef, transactionDoc({
      type: entry.type,
      amountMinor: entry.amountMinor,
      currency: entry.currency,
      occurredAt: entry.occurredAt,
      occurredOn: dayIn(entry.occurredAt, entry.timeZone),
      accountUid: account.uid,
      categoryUid: category?.uid ?? null,
      merchant: entry.merchant,
      note: entry.note,
      nowMillis,
      serverTime,
    }));
    if (merchantRef !== null && entry.merchant !== null) {
      // Uncategorised keeps what was learned before, as on the phone.
      const keptCategory = known?.exists && known.get("deleted") !== true ? (known.get("categoryUid") ?? null) : null;
      tx.set(merchantRef, merchantDoc({ name: entry.merchant, categoryUid: category?.uid ?? keptCategory, nowMillis, serverTime }));
    }
    return { txUid: txRef.id, accountUid: account.uid, categoryUid: category?.uid ?? null };
  });
}

/**
 * What an entry can name. Response: `{ accounts: [{ uid, name, kind, last4 }], categories:
 * [{ uid, name, kind }] }`: live ones only, built-in categories first.
 */
export async function listFinance(userUid: string, _data: unknown) {
  const user = userDoc(userUid);
  const [accounts, userCategories] = await Promise.all([
    user.collection("finAccounts").where("deleted", "==", false).get(),
    user.collection("finCategories").where("deleted", "==", false).get(),
  ]);
  return {
    accounts: accounts.docs
      .filter((doc) => doc.get("archived") !== true)
      .map((doc) => ({ uid: doc.id, name: doc.get("name") ?? "", kind: doc.get("kind") ?? "", last4: doc.get("last4") ?? null })),
    categories: [...BUILT_IN_CATEGORIES, ...userCategories.docs.map(toCategory)],
  };
}

function toCategory(doc: FirebaseFirestore.QueryDocumentSnapshot): FinCategory {
  return { uid: doc.id, name: String(doc.get("name") ?? ""), kind: String(doc.get("kind") ?? "") };
}

function userDoc(userUid: string): DocumentReference {
  return getFirestore().collection("users").doc(userUid);
}
