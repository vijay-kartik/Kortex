import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { test } from "node:test";
import {
  BUILT_IN_CATEGORIES, dayIn, FinAccount, merchantDoc, merchantUid, parseNewTransaction, payeeKey, resolveAccount, resolveCategory,
  transactionDoc,
} from "../financeDocs";

const NOW = Date.UTC(2026, 8, 29, 6, 30); // 29 Sep 2026, 12:00 in India

const accounts: FinAccount[] = [
  { uid: "checking", name: "Checking Account", kind: "BANK", last4: "4471" },
  { uid: "kortex", name: "KORTEX", kind: "CREDIT_CARD", last4: "8824" },
  { uid: "cash", name: "Personal Cash", kind: "CASH", last4: null },
];

test("a request is read with sensible defaults", () => {
  const entry = parseNewTransaction({ type: "expense", amountMinor: 4250, account: "8824", merchant: " Whole Foods " }, NOW);
  assert.equal(entry.type, "EXPENSE");
  assert.equal(entry.currency, "INR");
  assert.equal(entry.occurredAt, NOW);
  assert.equal(entry.timeZone, "Asia/Kolkata");
  assert.equal(entry.merchant, "Whole Foods");
  assert.equal(entry.category, null);
});

test("bad requests are refused", () => {
  assert.throws(() => parseNewTransaction({ type: "TRANSFER", amountMinor: 1, account: "x" }, NOW), /type must be EXPENSE or INCOME/);
  assert.throws(() => parseNewTransaction({ type: "EXPENSE", amountMinor: 0, account: "x" }, NOW), /amountMinor/);
  assert.throws(() => parseNewTransaction({ type: "EXPENSE", amountMinor: 42.5, account: "x" }, NOW), /amountMinor/);
  assert.throws(() => parseNewTransaction({ type: "EXPENSE", amountMinor: 100, account: "x", currency: "USD" }, NOW), /Only INR/);
  assert.throws(() => parseNewTransaction({ type: "EXPENSE", amountMinor: 100, account: "x", timeZone: "Mars/Olympus" }, NOW), /timeZone/);
  assert.throws(() => parseNewTransaction({ type: "EXPENSE", amountMinor: 100 }, NOW), /account is required/);
});

test("accounts match by uid, last 4 or name", () => {
  assert.equal(resolveAccount(accounts, "kortex").uid, "kortex");
  assert.equal(resolveAccount(accounts, "4471").uid, "checking");
  assert.equal(resolveAccount(accounts, " personal cash ").uid, "cash");
  assert.throws(() => resolveAccount(accounts, "9999"), /No account matches "9999". Yours: Checking Account \(••4471\), KORTEX \(••8824\), Personal Cash/);
  assert.throws(() => resolveAccount([...accounts, { uid: "two", name: "Card", kind: "CREDIT_CARD", last4: "8824" }], "8824"), /More than one account/);
  assert.throws(() => resolveAccount([], "anything"), /Add an account in Kortex first/);
});

test("categories match by uid or name, of the entry's kind only", () => {
  const categories = [...BUILT_IN_CATEGORIES, { uid: "c1", name: "Groceries", kind: "EXPENSE" }];
  assert.equal(resolveCategory(categories, "groceries", "EXPENSE")?.uid, "c1");
  assert.equal(resolveCategory(categories, "food", "EXPENSE")?.uid, "food");
  assert.equal(resolveCategory(categories, null, "EXPENSE"), null);
  assert.throws(() => resolveCategory(categories, "Salary", "EXPENSE"), /No expense category matches "Salary"/);
  assert.equal(resolveCategory(categories, "Salary", "INCOME")?.uid, "salary");
});

test("merchant ids match the app's FinanceIds", () => {
  assert.equal(payeeKey("  WHOLE FOODS   Market "), "whole foods market");
  const expected = "mer_" + createHash("sha256").update("whole foods market", "utf8").digest("hex").slice(0, 32);
  assert.equal(merchantUid("Whole Foods Market"), expected);
  assert.equal(merchantUid("WHOLE  FOODS MARKET"), expected);
});

test("the day is taken in the user's zone", () => {
  const lateUtc = Date.UTC(2026, 8, 28, 20, 0); // 01:30 on the 29th in India
  assert.equal(dayIn(lateUtc, "Asia/Kolkata"), "2026-09-29");
  assert.equal(dayIn(lateUtc, "UTC"), "2026-09-28");
});

test("documents carry every field the app's pull reads", () => {
  const doc = transactionDoc({
    type: "EXPENSE", amountMinor: 4250, currency: "INR", occurredAt: NOW, occurredOn: "2026-09-29", accountUid: "kortex",
    categoryUid: "food", merchant: "Whole Foods Market", note: null, nowMillis: NOW, serverTime: "TS",
  });
  assert.deepEqual(Object.keys(doc).sort(), [
    "accountUid", "amountMinor", "categoryUid", "createdAt", "currency", "deleted", "dueOn", "merchant", "note", "occurredAt",
    "occurredOn", "payeeKey", "receipt", "recurringUid", "serverUpdatedAt", "source", "sourceRef", "statementUid", "toAccountUid", "type",
    "updatedAt",
  ]);
  assert.equal(doc.source, "API");
  assert.equal(doc.payeeKey, "whole foods market");
  assert.equal(doc.deleted, false);
  assert.deepEqual(merchantDoc({ name: "Whole Foods Market", categoryUid: "food", nowMillis: NOW, serverTime: "TS" }), {
    payeeKey: "whole foods market", displayName: "Whole Foods Market", categoryUid: "food", updatedAt: NOW, serverUpdatedAt: "TS", deleted: false,
  });
});
