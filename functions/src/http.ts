import type { Request } from "firebase-functions/v2/https";
import { HttpsError } from "firebase-functions/v2/https";
import { logger } from "firebase-functions/v2";
import { Action, addLink, addTopicItem, createTopic } from "./actions";
import { bearerKey, userForApiKey } from "./apiKeys";

/** What `api` can do, by path: `POST /api/addLink` runs [addLink]. */
export const API_ROUTES: Record<string, Action> = { addLink, createTopic, addTopicItem };

interface ApiResponse {
  status: number;
  body: unknown;
}

/**
 * Serves `POST /api/{action}` with a JSON body (the same one the callable takes, unwrapped) and
 * `Authorization: Bearer kx_…`. Answers with the action's result, or
 * `{ error: { status, message } }` and the matching HTTP status.
 */
export async function handleApi(req: Pick<Request, "method" | "path" | "body" | "get">): Promise<ApiResponse> {
  if (req.method !== "POST") {
    return { status: 405, body: { error: { status: "method-not-allowed", message: "Use POST." } } };
  }
  try {
    const name = req.path.replace(/^\/+|\/+$/g, "");
    const action = Object.hasOwn(API_ROUTES, name) ? API_ROUTES[name] : undefined;
    if (!action) throw new HttpsError("not-found", `No such action. Use one of: ${Object.keys(API_ROUTES).join(", ")}.`);

    const key = bearerKey(req.get("authorization"));
    if (key === null) throw new HttpsError("unauthenticated", "Send your API key as Authorization: Bearer kx_…");
    const userUid = await userForApiKey(key);

    if (typeof req.body !== "object" || req.body === null) {
      throw new HttpsError("invalid-argument", "Send a JSON body with Content-Type: application/json.");
    }
    return { status: 200, body: await action(userUid, req.body) };
  } catch (e) {
    return errorResponse(e);
  }
}

export function errorResponse(e: unknown): ApiResponse {
  if (e instanceof HttpsError) {
    return { status: e.httpErrorCode.status, body: { error: { status: e.code, message: e.message } } };
  }
  logger.error("api failed", e);
  return { status: 500, body: { error: { status: "internal", message: "Something went wrong. Try again." } } };
}
