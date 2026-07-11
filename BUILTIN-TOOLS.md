# Builtin Tools

Living reference for every builtin tool the Kortex agent ships with — what it does, its
parameters, risk level, and implementation notes. **Update this file whenever a tool is
added, removed, or its behavior/signature changes.**

Builtin tools live in `core-agent/src/main/kotlin/dev/kortex/core/tool/builtin/` and are
assembled by `defaultTools()` in `BuiltinTools.kt`. The app wires them up in
`KortexContainer` via `ToolRegistry(defaultTools())`. Host (Android) tools live in
`app/src/main/kotlin/dev/kortex/app/tools/`.

## How tools work (infrastructure)

| Piece | File | Role |
|---|---|---|
| `Tool` interface | `core/tool/Tool.kt` | Typed, self-describing (emits JSON schema for function calling), tagged with a `RiskLevel`. Optional `promptHint` — a one-line usage hint shown in the system prompt's tool inventory. |
| `tool { }` DSL | `core/tool/ToolDsl.kt` | Ergonomic builder: `param(...)`, `risk(...)`, `promptHint(...)`, `execute { }`. |
| `ToolResult` | `core/tool/Tool.kt` | `ok` + `content`, plus optional `attachments: List<Attachment>` (base64 images/docs) that are injected into the LLM's context — how `read_file` feeds PDFs to the vision model. |
| `ToolGovernor` | `core/tool/ToolGovernor.kt` | Every call passes through it: budget check → required-param validation → risk approval (HITL) → audit entry. The LLM proposes; the governor disposes. |
| `ToolRegistry` | `core/tool/ToolRegistry.kt` | Holds tools; supports runtime enable/disable and `scoped()` allow-list views for sub-agents. |
| `RiskLevel` | `core/tool/Tool.kt` | `LOW` runs without asking; `MEDIUM`/`HIGH` require the user to approve via the `Approver` dialog. |

MCP tools (Composio Gmail, user-added servers) are **not** builtin — they are registered
dynamically at runtime by `McpToolSource` (`core/mcp/McpToolSource.kt`) into the same
registry, carrying their server's raw JSON schema. **Composio Gmail tools are opt-in:**
newly discovered `composio-gmail_*` tools start disabled
(`McpStore.defaultDisableNewTools`) and must be enabled per-tool in MCP Settings; the
user's toggle is remembered and never overridden on reconnect.

## Default tool set (`defaultTools()`)

Every agent starts with these six. Android-specific tools are added on top in the app
module (in `KortexContainer`, e.g. `defaultTools() + gmailTool(...)`).

### `calculator` — `Calculator.kt`
- **Description:** Evaluate an arithmetic expression and return the numeric result.
- **Params:** `expression` (string, required).
- **Risk:** LOW.
- **Behavior:** Deterministic recursive-descent evaluator (LLMs are unreliable at
  arithmetic). Supports `+ - * / %`, `^` (right-associative power), parentheses, unary
  minus, decimals. Integer-valued results are formatted without a decimal point. Pure
  Kotlin, no network — unit-tested in `CalculatorTest.kt`.

### `web_search` — `WebSearch.kt`
- **Description:** Search the web for current/factual information; returns top results as
  title, snippet, and URL.
- **Params:** `query` (string, required); `limit` (integer, optional, 1–8, default 5).
- **Risk:** LOW.
- **Behavior:** Scrapes DuckDuckGo's keyless HTML endpoint
  (`https://html.duckduckgo.com/html/`) with a mobile Chrome user-agent, regex-parses
  `result__a` / `result__snippet` anchors, and decodes DDG's `uddg=` redirect links to
  real URLs. Empty results still return `ok=true` with an explanatory message.
- **Caveats:** Can be rate-limited/blocked from datacenter IPs (more reliable on-device).
  Backend is swappable (Brave/Tavily/Bing) by changing only this factory. Uses a shared
  Ktor OkHttp client with 30s request / 15s connect timeouts.

### `open_url` — `WebFetch.kt`
- **Description:** Fetch a web page by URL and return its readable text. The follow-through
  for `web_search` — used when a snippet is incomplete or points at a live page.
- **Params:** `url` (string, required); `max_chars` (integer, optional, default 4000,
  clamped 500–12,000).
- **Risk:** LOW.
- **Behavior:** GETs the URL (same UA/client as `web_search`), refuses non-HTML/plain-text
  content types and pages over 5 MB up front, then extracts readable text without an HTML
  parser library: strips script/style/nav/header/footer/aside and comments, keeps `<body>`,
  converts block tags to newlines, unescapes entities, collapses whitespace, truncates to
  `max_chars`. JS-rendered pages may yield no text (returned as `ok=true` with a note).

### `current_time` — `BuiltinTools.kt`
- **Description:** Get the current local date and time (ISO-8601).
- **Params:** none (the canonical zero-arg tool example).
- **Risk:** LOW.
- **Behavior:** Returns `ZonedDateTime.now().toString()`.

### `analyze_statement` — `StatementAnalyzer.kt`
- **Description:** Formats bank/credit-card statement transactions into a Markdown table.
- **Params:** `transactions` (array, required) — JSON array of objects, each with `date`,
  `description`, `amount`; optional `category`.
- **Risk:** LOW.
- **Behavior:** The LLM does the actual extraction from attachments (images/PDFs in its
  context); this tool only validates and rigorously formats the extracted rows into a
  `| Date | Description | Category | Amount |` table. Missing fields render as `-`.
  Empty/missing array → `ok=false`.

### `read_file` — `ReadFileTool.kt`
- **Description:** Reads a local device file (downloaded PDFs, text, images) and attaches
  its content to the conversation for analysis.
- **Params:** `file_path` (string, required) — absolute path.
- **Risk:** LOW.
- **Behavior:** Branches on extension:
  - **PDF** — rendered page-by-page via Android `PdfRenderer` into JPEG images (2×
    resolution, quality 90, max 10 pages to bound payload size) and returned as
    `ToolResult.attachments` for the vision model.
  - **Text** (`txt`/`md`/`csv`/`json`) — contents returned inline in `content`
    (truncated at 100,000 chars), skipping attachment overhead.
  - **Images / other** — whole file base64-encoded as a single attachment
    (`png`/`jpg`/`jpeg`/`webp` get proper MIME types; unknown → `application/octet-stream`).
- **Note:** Pairs with `gmail_search`, which downloads email attachments to local paths
  this tool can then read. Uses Android graphics APIs — `core-agent` is now an Android
  library module, no longer pure JVM.

## Host (Android) tools — `app/.../tools/`

### `gmail_search` — `GmailTool.kt`
- **Description:** Search and read emails from the user's Gmail account via the Gmail
  REST API (`core/gmail/GmailApi.kt`). The LLM translates the user's request into Gmail
  search operators.
- **Params:** `query` (string, required — Gmail search syntax: `from:`, `subject:`,
  `is:unread`, `has:attachment`, `after:YYYY/MM/DD`, …); `max_results` (integer,
  optional, 1–10, default 5); `include_body` (string, optional, `'yes'`/`'no'`, default
  yes); `download_attachments` (string, optional, `'yes'`/`'no'`, default yes).
- **Risk:** **MEDIUM** — reads real user email, so the governor asks for approval.
- **Prompt hint:** Instructs the LLM to always convert the question into Gmail operators
  (e.g. "do I have new emails?" → `is:unread in:inbox`), never pass it verbatim.
- **Behavior:** Lists matching message IDs, fetches full content for each, and formats
  subject/from/to/cc/date/labels/ID plus the body (plain text, falling back to
  tag-stripped HTML, truncated at 4,000 chars). Attachments are downloaded to
  `cacheDir/gmail_attachments/` with sanitized filenames and their absolute paths
  reported (small `text/*` files under 8 KB are also inlined) — `read_file` can then open
  them. Requires an OAuth2 token from the injected `tokenProvider`; returns actionable
  errors when Gmail isn't connected, the token expired (401), or the `gmail.readonly`
  scope is missing (403).
- **Status:** Registered in `KortexContainer` on top of `defaultTools()`.

### `whatsapp_send_message` — `WhatsAppTool.kt`
- **Description:** Sends a real WhatsApp message to a named contact via Android 16
  Platform App Functions.
- **Params:** `contact_name` (string, required); `message` (string, required).
- **Risk:** **HIGH** — always triggers the human-in-the-loop approval dialog.
- **Behavior:** Requires Android 16+ (API 36 / "Baklava"). Uses reflection against
  `AppFunctionManager` / `ExecuteAppFunctionRequest` to invoke `com.whatsapp` →
  `sendMessage` with `recipientName` + `text`, bridging the `OutcomeReceiver` callback
  into a suspending call.
- **Status:** Defined but **not currently registered** in `KortexContainer` — the factory
  (`whatsappTool(context)`) has no call site. Wire it into the registry to activate.

## Adding a new builtin tool (checklist)

1. Create a factory in `core/tool/builtin/` (or `app/.../tools/` if it needs Android)
   using the `tool(name, description) { ... }` DSL.
2. Set an honest `risk(...)` — anything with side effects visible to the user or the
   outside world should be `MEDIUM`/`HIGH` so the governor asks first.
3. Return `ToolResult(ok, content)`; catch failures and return `ok=false` with a message
   the LLM can act on (never throw — the governor catches, but a good message beats a
   stack trace).
4. Add it to `defaultTools()` (core) or register it on the shared registry (app).
5. Unit-test pure logic in `core-agent/src/test/` (see `CalculatorTest`,
   `WebSearchParseTest`, `WebFetchParseTest` for the pattern).
6. **Update this file.**
