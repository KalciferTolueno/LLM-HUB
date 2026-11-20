import * as vscode from "vscode";

/** Shown to the model so it can emit machine-parsed file operations. */
export function buildAgentSystemPrompt(workspaceName: string): string {
  return `You are a coding assistant inside VS Code. Workspace folder name: "${workspaceName}".

To read existing files before editing, request one path per block (empty body). The user’s editor will paste file contents into the chat as the next message.

<<<BEARIS_READ path="relative/path.ts">>>
<<<END_BEARIS_READ>>>

To see which files exist (lightweight index), list a directory relative to the project root (use path="." for the whole project). You will receive a flat list of file paths (depth and count are capped).

<<<BEARIS_LIST path=".">>>
<<<END_BEARIS_LIST>>>

To search the project by keywords (TF‑IDF relevance over indexed text; index builds on first use or via command):

<<<BEARIS_SEARCH query="auth login handler">>>
<<<END_BEARIS_SEARCH>>>

For exact text or regex matches line-by-line (like ripgrep; good for symbols and string literals):

<<<BEARIS_GREP pattern="TODO|FIXME" path="." glob="**/*.{ts,tsx}" mode="regex">>>
<<<END_BEARIS_GREP>>>

Use mode="fixed" for literal substring search. Omit path for "." and glob for "**/*".

When creating or overwriting whole files, use:

<<<BEARIS_WRITE path="relative/path.ts">>>
full raw file contents here
<<<END_BEARIS_WRITE>>>

To edit an existing file with minimal output, use SEARCH/REPLACE hunks (each SEARCH block must match exactly once in the file):

<<<BEARIS_PATCH path="relative/path.ts">>>
<<<<<<< SEARCH
exact lines from file (enough context to be unique)
=======
replacement lines
>>>>>>> REPLACE
<<<END_BEARIS_PATCH>>>

Rules:
- Use only paths inside the workspace (no .. segments).
- Never spam or repeat the same filename or placeholder (e.g. “archivo.html” many times). Use each real path once inside BEARIS_WRITE or in prose.
- Prefer native OpenAI function/tool calls for file writes when available (create_new_file/write_file with JSON args). If tool-calls are unavailable, use BEARIS_WRITE / BEARIS_PATCH as fallback.
- If the user asks to create, save, or generate project files (e.g. “crea el archivo”, “hazme la web”), create one real file per output path (e.g. index.html, styles.css) instead of generating wrapper scripts.
- To edit a file you do not have in context: use BEARIS_LIST to find it, then BEARIS_READ, then reply with BEARIS_PATCH (small edits) or BEARIS_WRITE (full file). If the user has the file open, you may get its content in "## Active file" without READ.
- Prefer tool-only turns first (BEARIS_GREP / BEARIS_SEARCH / BEARIS_LIST / BEARIS_READ), then BEARIS_PATCH or BEARIS_WRITE after you see [Bearis: tool results]. Do not put tools and PATCH/WRITE in the same reply.
- Create only files the user asked for.
- Prefer BEARIS_PATCH for small edits to existing files; use BEARIS_WRITE for new files or full rewrites.
- Put complete file content between WRITE markers (not truncated).
- Brief natural language before or after blocks is fine.`;
}

/** When no workspace / folder is bound yet: model can still emit blocks; user picks folder + confirms before apply. */
export function buildAgentSystemPromptProvisional(): string {
  return `You are a coding assistant in VS Code. The user may be working without a workspace open: they will be asked to choose a folder on disk and confirm before any files are written.

To read files first:

<<<BEARIS_READ path="relative/path.ts">>>
<<<END_BEARIS_READ>>>

To list files under a folder (path="." for project root):

<<<BEARIS_LIST path=".">>>
<<<END_BEARIS_LIST>>>

To search indexed code (keyword / token overlap, not neural embeddings):

<<<BEARIS_SEARCH query="component props">>>
<<<END_BEARIS_SEARCH>>>

For literal or regex line matches:

<<<BEARIS_GREP pattern="myFunction" path="src" mode="fixed">>>
<<<END_BEARIS_GREP>>>

To write files:

<<<BEARIS_WRITE path="relative/path.ts">>>
full raw file contents here
<<<END_BEARIS_WRITE>>>

Edit existing files with hunks (search must match once):

<<<BEARIS_PATCH path="relative/path.ts">>>
<<<<<<< SEARCH
old lines
=======
new lines
>>>>>>> REPLACE
<<<END_BEARIS_PATCH>>>

Rules:
- No .. segments in paths.
- Never repeat the same filename or placeholder in a loop; use concrete paths once (e.g. index.html).
- Prefer native OpenAI function/tool calls for writes when available; use BEARIS_WRITE / BEARIS_PATCH only as fallback.
- Prefer BEARIS_PATCH for small edits to existing files, BEARIS_WRITE for new files or full rewrites; Markdown fences may still be saved when the extension offers it.
- Use GREP/SEARCH/LIST/READ first if you need locations or contents; after [Bearis: tool results], reply with PATCH or WRITE. Avoid tools and PATCH/WRITE in the same reply.
- Complete file contents in each WRITE block (not truncated).`;
}

/** OpenAI-style tools only: no BEARIS_* blocks; VS Code extension applies `tool_calls` from the LAN API. */
export function buildStrictOpenAiAgentSystemPrompt(workspaceName: string): string {
  return `You are a coding assistant in VS Code. Workspace folder: "${workspaceName}".

Use the provided function tools only to create or overwrite whole files. Paths are relative to the workspace root; never use ".." segments. Put the complete file body in the tool arguments.

The user message may include "## Active file" with the current editor buffer (truncated). Prefer tools for files the user asked to create or replace; use chat for short snippets or explanation only.

Rules:
- Call create_new_file / write_file with JSON arguments when the user wants files on disk.
- Prefer one file per tool call when writing several files.
- After you see a user message starting with "[Tool execution]", briefly confirm what was written or how to fix errors; do not repeat the same failing tool call in a loop.
- Do not emit BEARIS_READ, BEARIS_LIST, BEARIS_WRITE, BEARIS_PATCH, or other custom markup — this session uses OpenAI-style tools only.`;
}

export function buildStrictOpenAiAgentSystemPromptProvisional(): string {
  return `You are a coding assistant in VS Code.

The user may pick a folder on disk and confirm before files are written. Use only the provided function tools to create or overwrite files; paths are relative to that folder (no "..").

After a user message starting with "[Tool execution]", summarize results briefly.

Do not use BEARIS_* markup blocks; use OpenAI-style tool calls only.`;
}

const WRITE_OPEN = /<<<BEARIS_WRITE\s+path=["']([^"']+)["']\s*>>>/g;
const WRITE_CLOSE = "<<<END_BEARIS_WRITE>>>";

const PATCH_OPEN = /<<<BEARIS_PATCH\s+path=["']([^"']+)["']\s*>>>/g;
const PATCH_CLOSE = "<<<END_BEARIS_PATCH>>>";

const READ_OPEN = /<<<BEARIS_READ\s+path=["']([^"']+)["']\s*>>>/g;
const READ_CLOSE = "<<<END_BEARIS_READ>>>";

const LIST_OPEN = /<<<BEARIS_LIST\s+path=["']([^"']+)["']\s*>>>/g;
const LIST_CLOSE = "<<<END_BEARIS_LIST>>>";

const SEARCH_OPEN = /<<<BEARIS_SEARCH\s+query=["']([^"']*)["']\s*>>>/g;
const SEARCH_CLOSE = "<<<END_BEARIS_SEARCH>>>";

const GREP_OPEN = /<<<BEARIS_GREP\s+/g;
const GREP_CLOSE = "<<<END_BEARIS_GREP>>>";

export interface ParsedWrite {
  relativePath: string;
  content: string;
}

export interface ParsedRead {
  relativePath: string;
}

export interface ParsedList {
  relativePath: string;
}

export interface ParsedSearch {
  query: string;
}

export interface ParsedGrep {
  pattern: string;
  subpath: string;
  glob: string;
  mode: "regex" | "fixed";
}

export interface PatchHunk {
  search: string;
  replace: string;
}

export interface ParsedPatch {
  relativePath: string;
  hunks: PatchHunk[];
}

/**
 * Extract BEARIS_READ blocks (path-only).
 */
export function parseBearisReadBlocks(text: string): ParsedRead[] {
  const out: ParsedRead[] = [];
  let i = 0;
  while (i < text.length) {
    READ_OPEN.lastIndex = i;
    const m = READ_OPEN.exec(text);
    if (!m) break;
    const rel = m[1].trim().replace(/\\/g, "/");
    const start = m.index + m[0].length;
    const end = text.indexOf(READ_CLOSE, start);
    if (end < 0) break;
    out.push({ relativePath: rel });
    i = end + READ_CLOSE.length;
  }
  return out;
}

export function stripBearisReadBlocks(text: string): string {
  const re =
    /<<<BEARIS_READ\s+path=["'][^"']+["']\s*>>>[\s\S]*?<<<END_BEARIS_READ>>>/g;
  return text.replace(re, "").trimEnd();
}

/**
 * Extract BEARIS_LIST blocks (path of directory to list).
 */
export function parseBearisListBlocks(text: string): ParsedList[] {
  const out: ParsedList[] = [];
  let i = 0;
  while (i < text.length) {
    LIST_OPEN.lastIndex = i;
    const m = LIST_OPEN.exec(text);
    if (!m) break;
    const rel = m[1].trim().replace(/\\/g, "/");
    const start = m.index + m[0].length;
    const end = text.indexOf(LIST_CLOSE, start);
    if (end < 0) break;
    out.push({ relativePath: rel });
    i = end + LIST_CLOSE.length;
  }
  return out;
}

export function stripBearisListBlocks(text: string): string {
  const re =
    /<<<BEARIS_LIST\s+path=["'][^"']+["']\s*>>>[\s\S]*?<<<END_BEARIS_LIST>>>/g;
  return text.replace(re, "").trimEnd();
}

/**
 * Extract BEARIS_SEARCH blocks.
 */
export function parseBearisSearchBlocks(text: string): ParsedSearch[] {
  const out: ParsedSearch[] = [];
  let i = 0;
  while (i < text.length) {
    SEARCH_OPEN.lastIndex = i;
    const m = SEARCH_OPEN.exec(text);
    if (!m) break;
    const q = m[1].trim();
    const start = m.index + m[0].length;
    const end = text.indexOf(SEARCH_CLOSE, start);
    if (end < 0) break;
    if (q) out.push({ query: q });
    i = end + SEARCH_CLOSE.length;
  }
  return out;
}

export function stripBearisSearchBlocks(text: string): string {
  const re =
    /<<<BEARIS_SEARCH\s+query=["'][^"']*["']\s*>>>[\s\S]*?<<<END_BEARIS_SEARCH>>>/g;
  return text.replace(re, "").trimEnd();
}

function parseGrepAttrString(attrStr: string): ParsedGrep | null {
  const map = new Map<string, string>();
  const attrRe = /(\w+)=["']([^"']*)["']/g;
  let m: RegExpExecArray | null;
  while ((m = attrRe.exec(attrStr)) !== null) {
    map.set(m[1].toLowerCase(), m[2]);
  }
  const pattern = map.get("pattern")?.trim();
  if (!pattern) return null;
  const modeRaw = map.get("mode")?.toLowerCase() ?? "regex";
  const mode: "regex" | "fixed" = modeRaw === "fixed" ? "fixed" : "regex";
  return {
    pattern,
    subpath: map.get("path")?.trim() ?? ".",
    glob: map.get("glob")?.trim() ?? "**/*",
    mode,
  };
}

/**
 * Extract BEARIS_GREP blocks (attributes on opening line).
 */
export function parseBearisGrepBlocks(text: string): ParsedGrep[] {
  const out: ParsedGrep[] = [];
  let i = 0;
  while (i < text.length) {
    GREP_OPEN.lastIndex = i;
    const open = GREP_OPEN.exec(text);
    if (!open) break;
    const startAttrs = open.index + open[0].length;
    const closeOpen = text.indexOf(">>>", startAttrs);
    if (closeOpen < 0) break;
    const attrStr = text.slice(startAttrs, closeOpen);
    const afterTag = closeOpen + 3;
    const end = text.indexOf(GREP_CLOSE, afterTag);
    if (end < 0) break;
    const spec = parseGrepAttrString(attrStr);
    if (spec) out.push(spec);
    i = end + GREP_CLOSE.length;
  }
  return out;
}

export function stripBearisGrepBlocks(text: string): string {
  const re = /<<<BEARIS_GREP\s+[^>]+>>>[\s\S]*?<<<END_BEARIS_GREP>>>/g;
  return text.replace(re, "").trimEnd();
}

const SKIP_LIST_DIR_NAMES = new Set([
  "node_modules",
  ".git",
  ".svn",
  ".hg",
  "__pycache__",
  "dist",
  "build",
  ".next",
  "target",
  ".gradle",
  "venv",
  ".venv",
  "Pods",
  ".idea",
]);

function normalizeListRelativePath(rel: string): string {
  const t = rel.trim().replace(/\\/g, "/");
  if (t === "" || t === ".") return "";
  return t.replace(/^\/+/, "");
}

/**
 * Recursive directory listing under agent root (files only, forward-slash paths).
 */
export async function executeBearisLists(
  rootUri: vscode.Uri,
  lists: ParsedList[],
  maxDepth: number,
  maxEntries: number
): Promise<{ text: string; listOk: string[]; listErr: string[] }> {
  const depthCap = Math.min(Math.max(maxDepth, 0), 12);
  const entryCap = Math.min(Math.max(maxEntries, 50), 5000);
  const seenRoots = new Set<string>();
  const listOk: string[] = [];
  const listErr: string[] = [];
  const sections: string[] = [];
  let globalCount = 0;

  async function walkDir(
    dirUri: vscode.Uri,
    relPrefix: string,
    depth: number
  ): Promise<string[]> {
    const lines: string[] = [];
    if (globalCount >= entryCap) return lines;
    if (depth > depthCap) return lines;

    let entries: [string, vscode.FileType][];
    try {
      entries = await vscode.workspace.fs.readDirectory(dirUri);
    } catch (e) {
      return lines;
    }

    entries.sort((a, b) => a[0].localeCompare(b[0]));
    for (const [name, typ] of entries) {
      if (globalCount >= entryCap) break;
      if (SKIP_LIST_DIR_NAMES.has(name)) continue;

      const rel = relPrefix ? `${relPrefix}/${name}` : name;

      if (typ === vscode.FileType.File) {
        lines.push(rel);
        globalCount++;
      } else if (typ === vscode.FileType.Directory) {
        const sub = vscode.Uri.joinPath(dirUri, name);
        const subLines = await walkDir(sub, rel, depth + 1);
        lines.push(...subLines);
      }
    }
    return lines;
  }

  for (const item of lists) {
    const rel = normalizeListRelativePath(item.relativePath);
    if (seenRoots.has(rel)) continue;
    seenRoots.add(rel);

    const dirUri =
      rel === "" ? rootUri : fileUriUnderRoot(rootUri, rel);
    if (!dirUri) {
      listErr.push(rel || ".");
      sections.push(`### LIST ${rel || "."}\n(error: invalid path or outside root)\n`);
      continue;
    }

    try {
      const st = await vscode.workspace.fs.stat(dirUri);
      if (st.type !== vscode.FileType.Directory) {
        listErr.push(rel || ".");
        sections.push(`### LIST ${rel || "."}\n(error: not a directory)\n`);
        continue;
      }

      globalCount = 0;
      const lines = await walkDir(dirUri, rel, 0);
      const truncated = globalCount >= entryCap;
      listOk.push(rel || ".");
      const header = `### LIST ${rel || "."} (depth ≤ ${depthCap}, max ${entryCap} files)`;
      const body =
        lines.length > 0
          ? lines.join("\n")
          : "(no files in range — empty or only skipped folders)";
      const tail = truncated ? `\n\n… listing truncated at ${entryCap} files` : "";
      sections.push(`${header}\n\`\`\`\n${body}${tail}\n\`\`\`\n`);
    } catch (e) {
      listErr.push(rel || ".");
      const msg = e instanceof Error ? e.message : String(e);
      sections.push(`### LIST ${rel || "."}\n(error: ${msg})\n`);
    }
  }

  const text = sections.join("\n").trimEnd();
  return { text, listOk, listErr };
}

/**
 * Extract BEARIS_WRITE blocks from the model output.
 */
function parsePatchHunks(body: string): PatchHunk[] {
  const hunks: PatchHunk[] = [];
  const re =
    /<<<<<<< SEARCH\r?\n([\s\S]*?)\r?\n=======\r?\n([\s\S]*?)\r?\n>>>>>>> REPLACE/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(body)) !== null) {
    hunks.push({ search: m[1], replace: m[2] });
  }
  return hunks;
}

/**
 * Extract BEARIS_PATCH blocks (Aider-style SEARCH/REPLACE hunks).
 */
export function parseBearisPatchBlocks(text: string): ParsedPatch[] {
  const out: ParsedPatch[] = [];
  let i = 0;
  while (i < text.length) {
    PATCH_OPEN.lastIndex = i;
    const m = PATCH_OPEN.exec(text);
    if (!m) break;
    const rel = m[1].trim().replace(/\\/g, "/");
    const start = m.index + m[0].length;
    const end = text.indexOf(PATCH_CLOSE, start);
    if (end < 0) break;
    const body = text.slice(start, end);
    const hunks = parsePatchHunks(body);
    if (hunks.length > 0) out.push({ relativePath: rel, hunks });
    i = end + PATCH_CLOSE.length;
  }
  return out;
}

export function stripBearisPatchBlocks(text: string): string {
  const re =
    /<<<BEARIS_PATCH\s+path=["'][^"']+["']\s*>>>[\s\S]*?<<<END_BEARIS_PATCH>>>/g;
  return text.replace(re, "").trimEnd();
}

export function parseBearisWriteBlocks(text: string): ParsedWrite[] {
  const out: ParsedWrite[] = [];
  let i = 0;
  while (i < text.length) {
    WRITE_OPEN.lastIndex = i;
    const m = WRITE_OPEN.exec(text);
    if (!m) break;
    const rel = m[1].trim().replace(/\\/g, "/");
    const start = m.index + m[0].length;
    const end = text.indexOf(WRITE_CLOSE, start);
    if (end < 0) break;
    const content = text.slice(start, end);
    out.push({ relativePath: rel, content });
    i = end + WRITE_CLOSE.length;
  }
  return out;
}

/** Remove BEARIS_WRITE blocks before storing assistant message in history (keeps chat smaller). */
export function stripBearisWriteBlocks(text: string): string {
  const re =
    /<<<BEARIS_WRITE\s+path=["'][^"']+["']\s*>>>[\s\S]*?<<<END_BEARIS_WRITE>>>/g;
  return text.replace(re, "").trimEnd();
}

export function stripToolMarkersForHistory(text: string): string {
  return stripBearisWriteBlocks(
    stripBearisPatchBlocks(
      stripBearisListBlocks(
        stripBearisReadBlocks(
          stripBearisSearchBlocks(stripBearisGrepBlocks(text))
        )
      )
    )
  ).trimEnd();
}

/**
 * Read files under root for tool feedback. Per-file content is capped.
 */
export async function executeBearisReads(
  rootUri: vscode.Uri,
  reads: ParsedRead[],
  maxCharsPerFile: number
): Promise<{ text: string; readOk: string[]; readErr: string[] }> {
  const cap = Math.min(Math.max(maxCharsPerFile, 1024), 2_000_000);
  const seen = new Set<string>();
  const readOk: string[] = [];
  const readErr: string[] = [];
  const sections: string[] = [];

  for (const r of reads) {
    const rel = r.relativePath.trim();
    if (seen.has(rel)) continue;
    seen.add(rel);

    const target = fileUriUnderRoot(rootUri, rel);
    if (!target) {
      readErr.push(rel);
      sections.push(`### ${rel}\n(error: invalid path or outside root)\n`);
      continue;
    }

    try {
      const st = await vscode.workspace.fs.stat(target);
      if (st.type === vscode.FileType.Directory) {
        readErr.push(rel);
        sections.push(`### ${rel}\n(error: path is a directory)\n`);
        continue;
      }
      const raw = await vscode.workspace.fs.readFile(target);
      const decoder = new TextDecoder("utf-8", { fatal: false });
      let body = decoder.decode(raw);
      let truncated = false;
      if (body.length > cap) {
        body = body.slice(0, cap) + `\n\n… [truncated at ${cap} chars]`;
        truncated = true;
      }
      readOk.push(rel);
      const note = truncated ? " (truncated)" : "";
      sections.push(`### ${rel}${note}\n\`\`\`\n${body}\n\`\`\`\n`);
    } catch (e) {
      readErr.push(rel);
      const msg = e instanceof Error ? e.message : String(e);
      sections.push(`### ${rel}\n(error: ${msg})\n`);
    }
  }

  const text = sections.join("\n").trimEnd();
  return { text, readOk, readErr };
}

function isSafeRelativePath(rel: string): boolean {
  const norm = rel.replace(/\\/g, "/").replace(/^\/+/, "");
  if (!norm || norm.startsWith("/")) return false;
  const parts = norm.split("/").filter(Boolean);
  return !parts.some((p) => p === "..");
}

export function fileUriUnderRoot(root: vscode.Uri, relativePath: string): vscode.Uri | null {
  if (!isSafeRelativePath(relativePath)) return null;
  const segments = relativePath.split("/").filter(Boolean);
  try {
    const u = vscode.Uri.joinPath(root, ...segments);
    const rootLc = root.fsPath.replace(/\\/g, "/").toLowerCase();
    const fileLc = u.fsPath.replace(/\\/g, "/").toLowerCase();
    const under =
      fileLc === rootLc ||
      fileLc.startsWith(rootLc.endsWith("/") ? rootLc : rootLc + "/");
    if (!under) return null;
    return u;
  } catch {
    return null;
  }
}

async function ensureParentDirs(root: vscode.Uri, relativePath: string): Promise<void> {
  const segments = relativePath.split("/").filter(Boolean);
  const dirParts = segments.slice(0, -1);
  for (let i = 1; i <= dirParts.length; i++) {
    const dirUri = vscode.Uri.joinPath(root, ...dirParts.slice(0, i));
    await vscode.workspace.fs.createDirectory(dirUri);
  }
}

export async function applyBearisWrites(
  rootUri: vscode.Uri,
  writes: ParsedWrite[]
): Promise<{ written: string[]; errors: string[] }> {
  const written: string[] = [];
  const errors: string[] = [];
  const root = rootUri;

  for (const w of writes) {
    const rel = w.relativePath.trim();
    const target = fileUriUnderRoot(root, rel);
    if (!target) {
      errors.push(`Rejected path: ${rel}`);
      continue;
    }
    try {
      await ensureParentDirs(root, rel);
      await vscode.workspace.fs.writeFile(target, Buffer.from(w.content, "utf8"));
      written.push(rel);
    } catch (e) {
      errors.push(`${rel}: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  return { written, errors };
}

/** Path of file relative to root, or null if file is not under root. */
export function relativePathIfUnderRoot(
  rootUri: vscode.Uri,
  fileUri: vscode.Uri
): string | null {
  if (fileUri.scheme !== "file") return null;
  const rootLc = rootUri.fsPath.replace(/\\/g, "/").toLowerCase();
  const fileLc = fileUri.fsPath.replace(/\\/g, "/").toLowerCase();
  const under =
    fileLc === rootLc ||
    fileLc.startsWith(rootLc.endsWith("/") ? rootLc : rootLc + "/");
  if (!under) return null;
  const full = fileUri.fsPath;
  const root = rootUri.fsPath;
  let rel = full.startsWith(root) ? full.slice(root.length) : full;
  rel = rel.replace(/^[\\/]+/, "").replace(/\\/g, "/");
  return rel || null;
}

/** Optional appendix: active editor file snippet for the model. */
export function buildActiveFileAppendix(
  rootUri: vscode.Uri,
  maxChars: number
): string {
  const ed = vscode.window.activeTextEditor;
  if (!ed?.document) return "";
  const doc = ed.document;
  if (doc.isUntitled) return "";
  const fileUri = doc.uri;
  const rel = relativePathIfUnderRoot(rootUri, fileUri);
  if (!rel) return "";

  let body = doc.getText();
  if (body.length > maxChars) {
    body = body.slice(0, maxChars) + `\n\n… [truncated, ${doc.lineCount} lines total]`;
  }
  return `\n\n## Active file: ${rel}\n\`\`\`\n${body}\n\`\`\`\n`;
}
