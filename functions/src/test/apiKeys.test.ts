import assert from "node:assert/strict";
import { test } from "node:test";
import { HttpsError } from "firebase-functions/v2/https";
import { apiKeyId, bearerKey, newApiKey } from "../apiKeys";
import { errorResponse, handleApi } from "../http";

test("keys are long, random and prefixed", () => {
  const key = newApiKey();
  assert.match(key, /^kx_[A-Za-z0-9_-]{43}$/);
  assert.notEqual(newApiKey(), key);
});

test("a key's id is its SHA-256, so the key itself is never stored", () => {
  assert.equal(apiKeyId("kx_abc"), apiKeyId("kx_abc"));
  assert.match(apiKeyId("kx_abc"), /^[0-9a-f]{64}$/);
  assert.notEqual(apiKeyId("kx_abc"), apiKeyId("kx_abd"));
});

test("only a bearer kx_ key is read from the header", () => {
  assert.equal(bearerKey("Bearer kx_abc"), "kx_abc");
  assert.equal(bearerKey("bearer   kx_abc "), "kx_abc");
  assert.equal(bearerKey("Bearer eyJhbGciOi.firebase.token"), null);
  assert.equal(bearerKey("kx_abc"), null);
  assert.equal(bearerKey(undefined), null);
});

const request = (method: string, path: string, headers: Record<string, string> = {}) => ({
  method,
  path,
  body: {},
  get: ((name: string) => headers[name.toLowerCase()]) as never,
});

test("the api refuses what it can answer without the database", async () => {
  assert.equal((await handleApi(request("GET", "/addLink"))).status, 405);
  assert.equal((await handleApi(request("POST", "/deleteEverything", { authorization: "Bearer kx_a" }))).status, 404);
  assert.equal((await handleApi(request("POST", "/toString", { authorization: "Bearer kx_a" }))).status, 404);
  const noKey = await handleApi(request("POST", "/addLink"));
  assert.equal(noKey.status, 401);
  assert.deepEqual((noKey.body as { error: { status: string } }).error.status, "unauthenticated");
});

test("errors keep their callable code and get the matching HTTP status", () => {
  assert.deepEqual(errorResponse(new HttpsError("already-exists", "Taken")), {
    status: 409,
    body: { error: { status: "already-exists", message: "Taken" } },
  });
  assert.equal(errorResponse(new HttpsError("invalid-argument", "Bad")).status, 400);
});
