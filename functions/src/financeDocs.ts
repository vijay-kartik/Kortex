/**
 * Finance documents as the app's sync reads them (docs/FINANCE_PLAN.md › Firestore; `:sync`'s
 * `FinanceDocs.kt`). Field names are the contract: renaming one here breaks the app's pull. Money is
 * whole minor units (paise); a balance is never written, the app sums it from entries.
 */

import { createHash } from "node:crypto";
import { HttpsError } from "firebase-functions/v2/https";
import { Input, invalid } from "./input";

export type Doc = Record<string, unknown>;

export type EntryType = "EXPENSE" | "INCOME";

/** Seeded on every phone with these ids and never synced (`BuiltInCategories` in the app). */
export const BUILT_IN_CATEGORIES: ReadonlyArray<FinCategory> = [
  { uid: "food", name: "Food", kind: "EXPENSE" },
  { uid: "travel", name: "Travel", kind: "EXPENSE" },
  { uid: "utilities", name: "Utilities", kind: "EXPENSE" },
  { uid: "salary", name: "Salary", kind: "INCOME" },
];

export interface FinAccount {
  uid: string;
  name: string;
  kind: string;
  last4: string | null;
}

export interface FinCategory {
  uid: string;
  name: string;
  kind: string;
}

export interface NewTransaction {
  type: EntryType;
  amountMinor: number;
  currency: string;
  occurredAt: number;
  timeZone: string;
  account: string;
  category: string | null;
  merchant: string | null;
  note: string | null;
}

const MAX_AMOUNT_MINOR = 1_000_000_000_00; // ₹100 crore
const MAX_NAME = 200;
const MAX_NOTE = 2_000;

/**
 * Reads `addTransaction`'s request: `{ type, amountMinor, currency?, occurredAt?, timeZone?,
 * account, category?, merchant?, note? }`. `account` and `category` are a uid, a name, or for an
 * account its last 4 digits; they're matched by [resolveAccount] and [resolveCategory].
 */
export function parseNewTransaction(data: unknown, nowMillis: number): NewTransaction {
  const input = Input.of(data);
  const type = input.string("type", 20).toUpperCase();
  if (type !== "EXPENSE" && type !== "INCOME") throw invalid("type must be EXPENSE or INCOME.");
  const currency = (input.optionalString("currency", 3) ?? "INR").toUpperCase();
  if (currency !== "INR") throw invalid("Only INR is supported for now.");
  const timeZone = input.optionalString("timeZone", 64) ?? "Asia/Kolkata";
  if (!isTimeZone(timeZone)) throw invalid("timeZone must be an IANA zone, like Asia/Kolkata.");
  return {
    type,
    amountMinor: input.int("amountMinor", 1, MAX_AMOUNT_MINOR),
    currency,
    occurredAt: input.optionalMillis("occurredAt") ?? nowMillis,
    timeZone,
    account: input.string("account", MAX_NAME),
    category: input.optionalString("category", MAX_NAME),
    merchant: input.optionalString("merchant", MAX_NAME),
    note: input.optionalString("note", MAX_NOTE),
  };
}

/**
 * The account [ref] names: its uid, else its last 4 digits, else its name (ignoring case). Throws
 * `not-found` when none matches and `failed-precondition` when several do, listing them.
 */
export function resolveAccount(accounts: FinAccount[], ref: string): FinAccount {
  const byUid = accounts.find((a) => a.uid === ref);
  if (byUid) return byUid;
  const matches = /^\d{4}$/.test(ref)
    ? accounts.filter((a) => a.last4 === ref)
    : accounts.filter((a) => a.name.trim().toLowerCase() === ref.trim().toLowerCase());
  if (matches.length === 1) return matches[0];
  const choices = accounts.map((a) => (a.last4 ? `${a.name} (••${a.last4})` : a.name)).join(", ");
  if (matches.length === 0) {
    throw new HttpsError("not-found", accounts.length ? `No account matches "${ref}". Yours: ${choices}.` : "Add an account in Kortex first.");
  }
  throw new HttpsError("failed-precondition", `More than one account matches "${ref}". Use its uid: ${choices}.`);
}

/** The category [ref] names (uid or name, ignoring case) among those of [kind]; null when [ref] is null. */
export function resolveCategory(categories: FinCategory[], ref: string | null, kind: EntryType): FinCategory | null {
  if (ref === null) return null;
  const ofKind = categories.filter((c) => c.kind === kind);
  const match = ofKind.find((c) => c.uid === ref) ?? ofKind.find((c) => c.name.toLowerCase() === ref.toLowerCase());
  if (!match) {
    throw new HttpsError("not-found", `No ${kind.toLowerCase()} category matches "${ref}". Yours: ${ofKind.map((c) => c.name).join(", ")}.`);
  }
  return match;
}

/** How a merchant is matched, as `FinanceIds.payeeKey`: trimmed, lower case, inner spaces collapsed. */
export function payeeKey(raw: string): string {
  return raw.trim().toLowerCase().replace(/\s+/g, " ");
}

/** A merchant's document id, as `FinanceIds.merchant`: `mer_` and the first 32 hex of SHA-256. */
export function merchantUid(key: string): string {
  return "mer_" + createHash("sha256").update(payeeKey(key), "utf8").digest("hex").slice(0, 32);
}

/** The `yyyy-MM-dd` day [millis] falls on in [timeZone]: what the app groups days and months by. */
export function dayIn(millis: number, timeZone: string): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(millis));
}

export function transactionDoc(t: {
  type: EntryType;
  amountMinor: number;
  currency: string;
  occurredAt: number;
  occurredOn: string;
  accountUid: string;
  categoryUid: string | null;
  merchant: string | null;
  note: string | null;
  nowMillis: number;
  serverTime: unknown;
}): Doc {
  return {
    type: t.type,
    amountMinor: t.amountMinor,
    currency: t.currency,
    occurredAt: t.occurredAt,
    occurredOn: t.occurredOn,
    accountUid: t.accountUid,
    toAccountUid: null,
    categoryUid: t.categoryUid,
    merchant: t.merchant,
    payeeKey: t.merchant === null ? null : payeeKey(t.merchant),
    note: t.note,
    source: "API",
    sourceRef: null,
    recurringUid: null,
    dueOn: null,
    statementUid: null,
    receipt: null,
    createdAt: t.nowMillis,
    updatedAt: t.nowMillis,
    serverUpdatedAt: t.serverTime,
    deleted: false,
  };
}

/** The merchant remembered with the category picked for it, as the app's `AddTransaction` does. */
export function merchantDoc(m: { name: string; categoryUid: string | null; nowMillis: number; serverTime: unknown }): Doc {
  return {
    payeeKey: payeeKey(m.name),
    displayName: m.name,
    categoryUid: m.categoryUid,
    updatedAt: m.nowMillis,
    serverUpdatedAt: m.serverTime,
    deleted: false,
  };
}

function isTimeZone(zone: string): boolean {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}
