# Cloud sync (Firebase) — implementation plan

Goal: Links and Topics live in Firestore under the signed-in user, so an uninstall (or a new phone)
loses nothing, and a future **browser extension** can read and write the same records.

## Decisions

- **Scope: Links + Topics only.** Links (with tags), topics, topic items and topic summaries. Not
  synced: chat history, run traces, the knowledge graph, settings, API keys / OAuth tokens.
- **Records only, no files (for now).** The files topic items hold (docs, images, bill invoices) stay
  on the device that added them; an item's document carries the file's device path and type, and on
  any other device opening it says the file is on the phone that added it. Uploading them needs a
  Cloud Storage bucket (Blaze plan) and is left for later; the Storage parts below describe that.
- **Firestore is a record store, not a snapshot.** One document per link / topic / item, so the
  extension can add a single link without knowing about the rest. Room stays the app's source of
  truth for the UI; Firestore is the remote copy the two clients meet at.
- **Live sync while the app is on screen** (it replaced manual-only sync; see *Live sync* below).
  Settings › *Cloud sync* keeps **Sync now** and "Last synced …" as a fallback. No background
  worker: nothing syncs while Kortex is closed, and it catches up when it next opens. The protocol
  is multi-writer safe, because the extension is a second writer.
- **Firebase-default protection.** Encrypted at rest by Google; security rules let only
  `request.auth.uid == uid` read or write `users/{uid}/**`. No client-side encryption.
- **Auth: Firebase Auth + Google** via Credential Manager (`androidx.credentials` +
  `googleid`). This is separate from the existing Gmail `AccountManager` flow, which stays as it is.
- **New `:sync` module** (`dev.kortex.sync`) owns every Firebase dependency. `:links` and `:topics`
  stay Firebase-free: they only gain sync columns, triggers and a small `*SyncDao` each. `:app`
  hosts the Settings UI and wires Hilt.

## Identity

Local primary keys stay `Long` autoincrement (no churn in the UI or DAOs). Each synced row gains a
global `uid TEXT NOT NULL UNIQUE`, which is its Firestore document id.

| Row | `uid` |
|---|---|
| link | `sha256(urlKey)` in hex, first 32 chars. **Deterministic**, so if the phone and the extension save the same page, they write the same doc and can't make duplicates. The extension must port `linkUrlKey()` exactly (see `LinkUrlKey.kt`), so add shared test vectors. |
| topic | random UUID |
| topic item | random UUID |

Cross-references use uids in Firestore (`linkUid`, `topicUid`) and are resolved back to local Long
ids on pull.

## Firestore layout

```
users/{uid}/links/{linkUid}
  url, urlKey, title, tags: [String], imageUrl?, imageHidden, createdAt,
  updatedAt (client millis), serverUpdatedAt (serverTimestamp), deleted: Boolean
users/{uid}/topics/{topicUid}
  name, purpose?, pinned, sections: [String], createdAt, updatedAt, serverUpdatedAt, deleted,
  summary?: { text, generatedAt, fingerprint }
users/{uid}/topicItems/{itemUid}
  topicUid, type, addedAt, title?, text?, linkUid?, file?: { devicePath, mimeType },
  pageCount?, amountMinor?, currency?, issuedAt?, dueAt?, durationSeconds?, readingMinutes?,
  done, pinned, messageId?, threadId?, rfc822MessageId?, fromAddress?, accountEmail?, sentAt?,
  updatedAt, serverUpdatedAt, deleted
Storage (not built; see "Records only"): users/{uid}/topic-files/{itemUid}{ext}
```

- Items sit in one flat `topicItems` collection, so moving an item to another topic is a single field
  update.
- Tags are an array on the link. A tag no link uses isn't synced, since tags only live through links.
- **Deletes are soft** (`deleted: true`), so the other client sees them. They are never purged in v1.
- **Link thumbnails aren't uploaded.** On restore they're downloaded again from `imageUrl` through
  the existing `LinkImageStore.attachWhenReady`.

## Change tracking (local)

This needs no edits to existing DAO methods. It uses SQLite triggers, added in the migrations and in
a Room `onCreate` callback:

- New columns on `links`, `topics` and `topic_items`: `uid`, `dirty INTEGER NOT NULL DEFAULT 1`,
  and `updatedAtMillis` where it's missing. Kotlin defaults give new entities a uid and
  `dirty = true`.
- `AFTER UPDATE` on each table sets `dirty = 1, updatedAtMillis = now` for the row.
- `link_tags` insert/delete marks the owning link dirty, and `topic_summaries` upsert marks the
  topic dirty.
- `AFTER DELETE` on each table inserts `(kind, uid)` into a `sync_tombstones` table, one per DB.
- Every trigger has `WHEN (SELECT applying FROM sync_control) = 0`. `sync_control` is a
  one-row table that the sync engine sets to 1 inside the transaction where it applies remote
  changes, so pulled rows don't bounce back as dirty.
- Migrations: links v3→v4 and topics v4→v5. Existing rows get a uid backfilled in Kotlin (like
  `MIGRATION_2_3`) and start with `dirty = 1`, so the first sync uploads everything.

## Sync algorithm (`CloudSync.syncNow()`)

**Pull runs before push.** A newer remote change then replaces the local one before it could be
uploaded over it, so the push only ever carries changes that are the newest anywhere. (Pushing first
would overwrite, say, a newer title the extension wrote.)

1. **Push** (runs second). For each dirty row, `set(merge)` its doc with `updatedAt` and `serverUpdatedAt`, in
   batches of ≤ 500: deletes, then links, topics and items, so what an item refers to is in the cloud
   before it is. Tombstones become `deleted: true` writes. Then clear
   `dirty` and delete the pushed tombstones, but only for rows whose `updatedAtMillis` hasn't
   changed since they were read.
2. **Pull** (runs first). Query each collection with `serverUpdatedAt > lastPulledAt` (the watermark is
   stored in DataStore per account, and it's 0 on a fresh install, so the first pull is a full
   restore). The query starts a minute before the watermark, since server timestamps don't commit
   strictly in order; re-applying a doc is harmless. Pages of 300 are read from the server only
   (never Firestore's cache) and each is applied in its own transaction, advancing the watermark, so
   an interrupted restore resumes. Apply everything
   in one transaction per DB with `applying = 1`:
   - Order: links → topics → items, so cross-refs resolve.
   - **Last writer wins** on `updatedAt`. A remote doc older than a still-dirty local row is
     skipped, because the next push overwrites it.
   - `deleted` → delete the local row (cascades as today) plus its files and thumbnails.
   - **Unique clashes:** a topic name that's already taken locally by another uid is renamed
     "Name (2)". Links can't clash, because their uid is derived from `urlKey`.
   - An item whose `linkUid` or `topicUid` isn't known locally yet is held back: its uid is kept
     (`held_items:<uid>` in DataStore) and the next sync fetches it again by id, since the
     watermark has moved past it.
3. **Thumbnails.** New link thumbnails are queued through `LinkImageStore`. (Topic files would be
   downloaded here once they're uploaded; see "Records only".)
4. Save `lastPulledAt` as the max `serverUpdatedAt` seen, and "Last synced" as the current time.

**Signing out** keeps local data. The phone records which account its links belong to (the
*owner*, in the `cloud_sync` DataStore). **Signing into a different account** while the phone holds
the owner's links asks, before anything syncs, whether to *add them to the new account* (every link
is marked dirty and the old owner's unpushed deletes are dropped, so they never reach the new
account) or *remove them from this phone* (deleted with tracking off, so no account's cloud copy is
touched). Every change of owner resets the new owner's watermark, since the phone no longer holds
what was pulled up to it. `syncNow()` refuses to run while the owner isn't the signed-in account.
Each sign-in ends with a sync inside onboarding (Figma: Login & Logout 03/04). The pull first counts
the account's changed docs (one aggregate read); if there are any, onboarding shows *Restoring your
library* with live "n of total" progress, otherwise it goes straight to *All set*, whose summary card
shows what was restored and whether the sync finished. Topics and files join the restore card in
phase 4.

## Live sync (`CloudSync.runLive()`)

The signed-in app content runs it while the activity is at least STARTED (`ui/sync/LiveSync.kt`), so
it starts only after onboarding has restored the library.

1. **Catch up** with a full `syncNow()`: pull, then push.
2. **Listen** to `links`, `topics` and `topicItems` with snapshot listeners on the same
   `serverUpdatedAt > watermark − 1 min` query the pull uses. Each change batch is applied through
   the same DAO calls as a pull, under the same lock. Writes this phone hasn't had confirmed yet
   are skipped; they arrive again once confirmed, and applying our own echo changes nothing. Only
   server answers (not memory-cache ones) move the watermark. After links or topics arrive,
   held-back items are retried. A dropped listener reconnects with backoff (2 s doubling to 1 min).
3. **Push on save.** Rows only turn dirty when something is saved (saving a link, adding an item,
   creating or editing a topic, deleting), never while a form is being filled in. Room flows over
   the dirty counts and tombstones see the save, and the push follows 300 ms later, just enough to
   send saves that land together (a multi-select move, a delete's cascade) as one. A failed push retries with backoff (5 s doubling to 5 min).
4. **On leaving the screen**, listeners stop and one last push runs in the app scope.

## Cloud Functions (`functions/`)

Three callable functions (region `asia-south1`, Node 22, TypeScript) write straight into
`users/{uid}/**` for clients without the app's database: the browser extension, shortcuts,
the agent. They write the same documents as the app's push, so the phone picks them up live (or on its
next sync). Each needs a signed-in Firebase user and works only on that user's records.

| Function | Request | Response |
|---|---|---|
| `addLink` | `{ url, title?, tags?, imageUrl? }` | `{ linkUid, created }`; `created: false` when the page is already saved (left untouched) |
| `createTopic` | `{ name, purpose?, pinned? }` | `{ topicUid }`; `already-exists` when the name is taken, ignoring case |
| `addTopicItem` | `{ topicUid, item: { type, … }, pinned? }` | `{ itemUid, linkUid? }` |

`addTopicItem` takes every `ItemType` except the file-backed ones, checked by the app's `AddItem`
rules:

- **Note** `text`
- **Link / Article / Video** `url`, `title?` (Article `readingMinutes?`, Video `durationSeconds?`,
  both `done?`). The link goes into the Links library too unless it's there already;
  `already-exists` if the topic already holds it.
- **Bill** `title`, `amountMinor`, `currency` (ISO 4217), `issuedAt?`, `dueAt?`, `paid?`, with no
  invoice.
- **Email** `messageId`, `threadId?`, `subject?`, `from?`, `snippet?`, `sentAt?`,
  `rfc822MessageId?`, `accountEmail?`

**Doc** and **Image** are refused (`failed-precondition`) until Cloud Storage is set up, since an
item's `file` only names a path on the device that added it. `functions/src/linkKey.ts` ports
`linkUrlKey`/`linkUid` and is tested against the same vectors as `LinkUrlKeyTest`/`LinkUidTest`.

Build and test with `npm --prefix functions test`; deploy with `firebase deploy --only functions`
(needs the Blaze plan).

## Security rules

```
// Firestore
match /users/{uid}/{document=**} { allow read, write: if request.auth != null && request.auth.uid == uid; }
// Storage — read and write are split because request.resource is null on a read,
// so a combined rule carrying the size check would reject every download.
match /users/{uid}/{allPaths=**} {
  allow read:  if request.auth != null && request.auth.uid == uid;
  allow write: if request.auth != null && request.auth.uid == uid
               && request.resource.size < 64 * 1024 * 1024;
}
```

These live in `firebase/firestore.rules` and `firebase/storage.rules`, and are deployed with
`firebase deploy --only firestore:rules,storage`.

## Firebase project setup

Project **`kortex-a24b7`** (project number `201049504537`). Most of this is done through the
`firebase` CLI from the repo root — `.firebaserc` pins the project, and `firebase.json` points at
the rules in `firebase/`.

Done:

- [x] Firebase project, with the Android app `dev.kortex.app` registered.
- [x] Debug SHA-1 and SHA-256 registered (`firebase apps:android:sha:list <appId>` to check). The
      **release** SHA-1 still has to be added before the first signed build.
- [x] **Firestore** created (Native mode, Standard edition, `asia-south1`), with the rules above
      deployed from `firebase/firestore.rules`.
- [x] `google-services.json` in `app/`, gitignored.
- [x] **Authentication › Google** enabled, `google-services.json` refreshed (it now carries the
      `oauth_client` entry), and the **web** client id put in `local.properties` as
      `FIREBASE_WEB_CLIENT_ID`, reachable from code as `BuildConfig.FIREBASE_WEB_CLIENT_ID`.
      Credential Manager's `setServerClientId()` wants that web id, never the Android one.

Still to do in the console (no CLI equivalent):

1. **Storage › Get started** (only once topic files are uploaded; not needed for records-only sync)
   — create the default bucket, in **`asia-south1`** to match Firestore.
   Then `firebase deploy --only storage` pushes `firebase/storage.rules`.

Don't let the CLI provision Firestore or Storage implicitly: `firebase deploy` will silently create
a missing default database in `nam5`, and both locations are permanent.

## Phases

0. **Firebase wiring.** Version catalog entries (Firebase BoM, auth, firestore, storage, credentials,
   googleid, google-services plugin); the `:sync` module skeleton; `google-services.json` gitignored.
1. **Local change tracking.** Add the uid, dirty and tombstone columns, triggers and `sync_control` to
   `links.db` and `topics.db`, with migrations and backfill, plus a `LinkSyncDao` and a
   `TopicSyncDao` that read dirty rows and apply remote ones. Unit-test the uid derivation and the
   merge rules.
2. **Auth.** `CloudAccount` (sign in with Google, sign out, current user flow), and a Settings ›
   *Cloud sync* section showing the account, a sign-in/out button and "Last synced".
3. **Push + pull for Links.** *(Implemented: `:sync` › `CloudSync`, `links/LinkSync`, `links/LinkDocs`;
   Settings › Cloud sync has "Sync now" and "Last synced".)* Start with the smallest part to prove the protocol end to end:
   uninstall, reinstall, sign in, sync, and every link is back.
4. **Topics.** Topics, items and summaries. *(Implemented, records only: `:sync` ›
   `topics/TopicSync`, `topics/TopicDocs`, and the shared `SyncRemote`. The restore screen and
   account switch cover topics too. File upload through Storage is deferred.)*
5. **Hardening.** Handle the account switch *(done: see "Signing out" above)*, show sync errors in Settings, and write the rules for the
   extension (the `linkUrlKey` test vectors and the doc schema above), which becomes the extension's
   contract.

## To do (sync failures, deferred on 2026-09-25)

Today a failed upload is never lost: rows stay dirty and tombstones stay put until a commit
succeeds, live sync retries with backoff (5 s doubling to 5 min), the app pushes once more when it
leaves the screen, and the next launch's catch-up sync pushes whatever is left. What's missing is
telling the user, and not retrying errors that can't fix themselves:

- [ ] **Sync status on the Settings card** (Figma: Login & Logout 10, "Sync failed · 2 h ago" with
      a Retry chip). `CloudSync` exposes the last live failure and whether a retry is scheduled;
      the card shows "Synced · 2 min ago", "Syncing…", or "Sync failed · retrying" in amber.
- [ ] **"N changes not synced"** on the card, from `observeUnpushedCount()` of both DAOs, so a
      stuck backlog is visible even when no error was reported.
- [ ] **Permission and auth errors aren't retried.** `PERMISSION_DENIED` / `UNAUTHENTICATED` stop
      the retry loop and ask the user to sign in again, instead of backing off forever.
- [ ] **Items whose link is gone.** An item whose `linkId` is no longer in links.db is skipped on
      every push and stays dirty; either push it without the link or delete it with the link.
- [ ] **Log out with unsynced changes** (Figma: Login & Logout 08): the confirm dialog's amber line,
      "3 changes haven't been backed up yet", from the same counts.
