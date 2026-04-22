# Bearis Chat (VS Code)

Minimal VS Code extension to chat with **[Bearis](https://github.com/)** on your phone over the **local OpenAI-compatible API** (`/v1/chat/completions` with SSE).

## Requirements

1. **Bearis** on Android: load an LLM, start **Local network API** (same Wi‑Fi as your PC).
2. Note your phone’s IP (e.g. `192.168.0.2`) and port **8080**.

## Setup

```bash
cd vscode/bearis-chat
npm install
npm run compile
```

### Run in development

1. Open the folder `vscode/bearis-chat` in VS Code.
2. **Run and Debug** → **Launch Extension** (or F5).
3. In the Extension Development Host, open Command Palette → **Bearis: Open Chat**.
4. If your phone IP differs, set **Settings → Bearis: Api Base Url** to  
   `http://<PHONE_IP>:8080/v1`

### Install as `.vsix`

```bash
npm install -g @vscode/vsce
npm run compile
vsce package
```

Then in VS Code: **Extensions** → **⋯** → **Install from VSIX…** → select the generated `.vsix`.

## Usage

- Command: **Bearis: Open Chat**
- Sends `POST .../v1/chat/completions` with `stream: true`, `model: "local"`.
- Conversation for the session is kept in memory (cleared when you close the panel).

## Agent mode (v0.9)

The model can **read** then **write** files under:

- the **first workspace folder** when you have a folder/workspace open, or  
- a path you choose with **Bearis: Set Agent Folder…** (saved for windows with no workspace).

**Grep tool** (line scan with literal or **JavaScript regex**, Cursor/ripgrep-style for exact matches):

```text
<<<BEARIS_GREP pattern="mySymbol" path="src" glob="**/*.ts" mode="fixed">>>
<<<END_BEARIS_GREP>>>
```

**Project rules**: add `bearis.md` and/or `.bearisrules` at the project root; they are appended to the system prompt when `bearis.injectProjectRules` is on (similar idea to `.cursorrules`).

**Search tool** (keyword relevance over an on-disk **TF‑IDF** index — mathematical sparse vectors, not a neural embedding model):

```text
<<<BEARIS_SEARCH query="auth handler">>>
<<<END_BEARIS_SEARCH>>>
```

The index is built on first search or via **Bearis: Refresh Code Index**. It is stored under the extension’s workspace/global storage. For true semantic search (sentence embeddings), you’d need an optional heavy dependency (e.g. ONNX) or a remote embedding API — not included by default.

**List tool** (recursive file paths under a folder; skips `node_modules`, `.git`, etc.):

```text
<<<BEARIS_LIST path=".">>>
<<<END_BEARIS_LIST>>>
```

**Read tool** (one path per block; extension injects file contents as the next user message):

```text
<<<BEARIS_READ path="src/example.ts">>>
<<<END_BEARIS_READ>>>
```

**Patch tool** (edit existing files; each `SEARCH` block must match **exactly once** in the file):

```text
<<<BEARIS_PATCH path="src/example.ts">>>
<<<<<<< SEARCH
old lines
=======
new lines
>>>>>>> REPLACE
<<<END_BEARIS_PATCH>>>
```

**Write tool** (after you confirm, if enabled):

```text
<<<BEARIS_WRITE path="src/example.ts">>>
// full file contents
<<<END_BEARIS_WRITE>>>
```

Prefer **read-only** replies first, then **write** replies after `[Bearis: read results]` (mixing READ+WRITE in one reply runs reads only and shows a warning).

**Markdown fences:** if the model answers with normal fenced code blocks (e.g. language `html`, `css`) and there is no `BEARIS_WRITE`, the extension can **infer filenames** (`index.html`, `styles.css`, …) and prompt to save them (`bearis.autoApplyMarkdownFences`). You can also put a relative path on the opening fence line (e.g. `src/App.tsx`).

Settings: `bearis.agentMode`, `bearis.autoApplyMarkdownFences`, `bearis.injectProjectRules`, `bearis.projectRulesMaxChars`, `bearis.maxAgentSteps`, `bearis.codeIndexEnabled`, `bearis.codeIndexMaxFiles`, `bearis.codeIndexMaxFileBytes`, `bearis.codeIndexChunkChars`, `bearis.searchMaxResults`, `bearis.grepMaxFiles`, `bearis.grepMaxMatches`, `bearis.grepMaxLineChars`, `bearis.listMaxDepth`, `bearis.listMaxEntries`, `bearis.maxReadFileChars`, `bearis.confirmWrites`, `bearis.includeActiveFile`, `bearis.maxActiveFileChars`.

## Limitations

- No persistence across panel close.
- Writes stay under the workspace folder or the folder you picked; quality depends on the on-device model following the `BEARIS_WRITE` format.
- Same practical limits as calling the API from the browser: small models, Wi‑Fi, server must stay on.

## Privacy

Traffic goes **only** to the URL you configure (typically your phone on the LAN), not to Bearis cloud services from this extension.
