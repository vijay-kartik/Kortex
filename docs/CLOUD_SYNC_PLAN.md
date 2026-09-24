# Cloud sync (Firebase) — implementation plan

Goal: Links and Topics live in Firestore under the signed-in user, so an uninstall (or a new phone)
loses nothing, and a future **browser extension** can read and write the same records.

## Decisions

- **Scope: Links + Topics only.** Links (with tags), topics, topic items, topic summaries, and the
  files topic items hold (docs, images, bill invoices). Not synced: chat history, run traces, the
  knowledge graph, settings, API keys / OAuth tokens.
- **Firestore is a record store, not a snapshot.** One document per link / topic / item, so the
  extension can add a single link without knowing about the rest. Room stays the app's source of
  truth for the UI; Firestore is the remote copy the two clients meet at.
- **Manual sync only.** Settings › *Cloud sync* has **Sign in with Google** and **Sync now** (with
  "Last synced …"). No background worker, and no Firestore snapshot listeners. A single device is
  supported, but the protocol is multi-writer safe because the extension is a second writer.
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
  topicUid, type, addedAt, title?, text?, linkUid?, file?: { storagePath, mimeType, name },
  pageCount?, amountMinor?, currency?, issuedAt?, dueAt?, durationSeconds?, readingMinutes?,
  done, pinned, messageId?, threadId?, rfc822MessageId?, fromAddress?, accountEmail?, sentAt?,
  updatedAt, serverUpdatedAt, deleted
Storage: users/{uid}/topic-files/{itemUid}{ext}
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

## Sync algorithm (`SyncEngine.syncNow()`)

1. **Push.** For each dirty row, `set(merge)` its doc with `updatedAt` and `serverUpdatedAt`, in
   batches of ≤ 500. Before an item's doc is written, its file is uploaded if it isn't in Storage
   yet (`remoteFilePath` column). Tombstones become `deleted: true` writes. Then clear
   `dirty` and delete the pushed tombstones, but only for rows whose `updatedAtMillis` hasn't
   changed since they were read.
2. **Pull.** Query each collection with `serverUpdatedAt > lastPulledAt` (the watermark is stored in
   DataStore, and it's 0 on a fresh install, so the first pull is a full restore). Apply everything
   in one transaction per DB with `applying = 1`:
   - Order: links → topics → items, so cross-refs resolve.
   - **Last writer wins** on `updatedAt`. A remote doc older than a still-dirty local row is
     skipped, because the next push overwrites it.
   - `deleted` → delete the local row (cascades as today) plus its files and thumbnails.
   - **Unique clashes:** a topic name that's already taken locally by another uid is renamed
     "Name (2)". Links can't clash, because their uid is derived from `urlKey`.
   - An item whose `linkUid` or `topicUid` isn't known locally yet is held back until the next sync.
3. **Files.** Missing topic files are downloaded into `topic-files/` after the pull, and a failed
   download leaves the item with a placeholder until the next sync. New link thumbnails are queued
   through `LinkImageStore`.
4. Save `lastPulledAt` as the max `serverUpdatedAt` seen, and "Last synced" as the current time.

**Signing out** keeps local data and clears the watermark. **Signing into a different account**
asks whether to merge the local data into it or wipe it first.

## Security rules

```
// Firestore
match /users/{uid}/{document=**} { allow read, write: if request.auth != null && request.auth.uid == uid; }
// Storage
match /users/{uid}/{allPaths=**} { allow read, write: if request.auth != null && request.auth.uid == uid
                                   && request.resource.size < 64 * 1024 * 1024; }
```

## What you set up in the Firebase console (Claude can't do this)

1. Create a Firebase project and add the Android app `dev.kortex.app`.
2. Register the debug SHA-1 (`./gradlew :app:signingReport`), plus the release SHA-1 later.
3. Enable **Authentication › Google**, and copy the **Web client ID** into `local.properties` as
   `FIREBASE_WEB_CLIENT_ID=…`.
4. Create **Firestore** (production mode) and **Storage**, then paste the rules above.
5. Download `google-services.json` into `app/`, and add it to `.gitignore`.

## Phases

0. **Firebase wiring.** Version catalog entries (Firebase BoM, auth, firestore, storage, credentials,
   googleid, google-services plugin); the `:sync` module skeleton; `google-services.json` gitignored.
1. **Local change tracking.** Add the uid, dirty and tombstone columns, triggers and `sync_control` to
   `links.db` and `topics.db`, with migrations and backfill, plus a `LinkSyncDao` and a
   `TopicSyncDao` that read dirty rows and apply remote ones. Unit-test the uid derivation and the
   merge rules.
2. **Auth.** `CloudAccount` (sign in with Google, sign out, current user flow), and a Settings ›
   *Cloud sync* section showing the account, a sign-in/out button and "Last synced".
3. **Push + pull for Links.** Start with the smallest part to prove the protocol end to end:
   uninstall, reinstall, sign in, sync, and every link is back.
4. **Topics + files.** Topics, items and summaries, uploading and downloading topic files through
   Storage.
5. **Hardening.** Handle the account switch, show sync errors in Settings, and write the rules for the
   extension (the `linkUrlKey` test vectors and the doc schema above), which becomes the extension's
   contract.
