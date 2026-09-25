/**
 * Entry points. Each action in `actions.ts` is reachable two ways:
 *
 * - **Callable** (`addLink`, `createTopic`, `addTopicItem`) for clients signed in with Firebase
 *   Auth: the app, the browser extension.
 * - **`api`**, plain HTTPS for scripts, shortcuts and other devices: `POST /api/{action}` with a
 *   JSON body and `Authorization: Bearer <personal API key>`.
 *
 * API keys are made, listed and revoked through callables only, so a key can never mint another.
 */

import { initializeApp } from "firebase-admin/app";
import { setGlobalOptions } from "firebase-functions/v2";
import { CallableRequest, HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { Action, addLink as addLinkAction, addTopicItem as addTopicItemAction, createTopic as createTopicAction } from "./actions";
import { createApiKey as createApiKeyAction, listApiKeys as listApiKeysAction, revokeApiKey as revokeApiKeyAction } from "./apiKeys";
import { handleApi } from "./http";

initializeApp();
// Next to Firestore (asia-south1), which can't move.
setGlobalOptions({ region: "asia-south1", maxInstances: 10 });

export const addLink = callable(addLinkAction);
export const createTopic = callable(createTopicAction);
export const addTopicItem = callable(addTopicItemAction);

export const createApiKey = callable(createApiKeyAction);
export const listApiKeys = callable(listApiKeysAction);
export const revokeApiKey = callable(revokeApiKeyAction);

// CORS is safe to open: a key travels in a header, never a cookie, so another site can't borrow one.
export const api = onRequest({ cors: true }, async (req, res) => {
  const { status, body } = await handleApi(req);
  res.status(status).json(body);
});

function callable(action: Action) {
  return onCall((request) => action(signedInUser(request), request.data));
}

function signedInUser(request: CallableRequest): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to Kortex first.");
  return uid;
}
