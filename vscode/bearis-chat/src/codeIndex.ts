import * as crypto from "crypto";
import * as vscode from "vscode";
import type { ParsedSearch } from "./agent";

const INDEX_FORMAT_VERSION = 1;
const EXCLUDE_GLOB =
  "**/{node_modules,.git,.svn,.hg,.idea,dist,build,out,.next,target,__pycache__,Pods,.venv,venv}/**";

export interface CodeIndexBuildOptions {
  maxFiles: number;
  maxFileBytes: number;
  chunkChars: number;
}

interface Chunk {
  path: string;
  line: number;
  preview: string;
  tf: Map<string, number>;
}

function tokenize(s: string): string[] {
  const out: string[] = [];
  const lower = s.toLowerCase();
  const re = /[a-z0-9][a-z0-9._-]{0,80}/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(lower)) !== null) {
    const w = m[0];
    if (w.length >= 2) out.push(w);
  }
  return out;
}

function chunkText(
  relPath: string,
  text: string,
  chunkChars: number
): Array<{ line: number; body: string }> {
  const lines = text.split(/\r?\n/);
  const chunks: Array<{ line: number; body: string }> = [];
  let buf: string[] = [];
  let startLine = 1;
  let len = 0;

  const flush = (endLine: number) => {
    if (buf.length === 0) return;
    const body = buf.join("\n");
    chunks.push({ line: startLine, body });
    buf = [];
    len = 0;
    startLine = endLine;
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const lineLen = line.length + 1;
    if (len + lineLen > chunkChars && buf.length > 0) {
      flush(i + 1);
    }
    if (buf.length === 0) startLine = i + 1;
    buf.push(line);
    len += lineLen;
  }
  flush(lines.length + 1);
  return chunks;
}

function previewOf(body: string, max = 420): string {
  const one = body.replace(/\s+/g, " ").trim();
  return one.length <= max ? one : one.slice(0, max) + "…";
}

export function indexStorageUri(
  context: vscode.ExtensionContext,
  rootFsPath: string
): vscode.Uri {
  const base = context.storageUri ?? context.globalStorageUri;
  const h = crypto.createHash("sha256").update(rootFsPath).digest("hex").slice(0, 24);
  return vscode.Uri.joinPath(base, "bearis-code-index", `${h}.json`);
}

export class CodeIndexManager {
  private chunks: Chunk[] = [];
  private idf = new Map<string, number>();
  private indexedRoot = "";

  get chunkCount(): number {
    return this.chunks.length;
  }

  get root(): string {
    return this.indexedRoot;
  }

  clear(): void {
    this.chunks = [];
    this.idf.clear();
    this.indexedRoot = "";
  }

  private recomputeIdf(): void {
    const N = this.chunks.length;
    const df = new Map<string, number>();
    for (const ch of this.chunks) {
      const seen = new Set<string>();
      for (const t of ch.tf.keys()) {
        if (seen.has(t)) continue;
        seen.add(t);
        df.set(t, (df.get(t) ?? 0) + 1);
      }
    }
    this.idf.clear();
    for (const [term, d] of df) {
      this.idf.set(term, Math.log((N + 1) / (d + 1)) + 1);
    }
  }

  private idfWeight(term: string): number {
    const N = this.chunks.length;
    return this.idf.get(term) ?? Math.log(N + 1) + 1;
  }

  /** Log-TF × IDF, L2-normalized sparse vector (cosine-ready). */
  private normalizeTfidf(tf: Map<string, number>): Map<string, number> {
    const v = new Map<string, number>();
    let norm = 0;
    for (const [t, c] of tf) {
      const w = (1 + Math.log(c)) * this.idfWeight(t);
      v.set(t, w);
      norm += w * w;
    }
    const n = Math.sqrt(norm) || 1;
    for (const k of v.keys()) {
      v.set(k, (v.get(k) ?? 0) / n);
    }
    return v;
  }

  search(query: string, topK: number): Array<{
    path: string;
    line: number;
    score: number;
    snippet: string;
  }> {
    const terms = tokenize(query);
    if (terms.length === 0 || this.chunks.length === 0) return [];

    const qtf = new Map<string, number>();
    for (const t of terms) {
      qtf.set(t, (qtf.get(t) ?? 0) + 1);
    }
    const qvec = this.normalizeTfidf(qtf);

    const scored: Array<{
      path: string;
      line: number;
      score: number;
      snippet: string;
    }> = [];

    for (const ch of this.chunks) {
      const dvec = this.normalizeTfidf(ch.tf);
      let dot = 0;
      for (const [t, qw] of qvec) {
        const dw = dvec.get(t);
        if (dw !== undefined) dot += qw * dw;
      }
      if (dot > 0) {
        scored.push({
          path: ch.path,
          line: ch.line,
          score: dot,
          snippet: ch.preview,
        });
      }
    }

    scored.sort((a, b) => b.score - a.score);
    return scored.slice(0, topK);
  }

  async load(context: vscode.ExtensionContext, rootUri: vscode.Uri): Promise<boolean> {
    const path = indexStorageUri(context, rootUri.fsPath);
    try {
      const raw = await vscode.workspace.fs.readFile(path);
      const j = JSON.parse(Buffer.from(raw).toString("utf8")) as {
        v: number;
        root: string;
        chunks: Array<{ p: string; l: number; pr: string; t: [string, number][] }>;
      };
      if (j.v !== INDEX_FORMAT_VERSION || j.root !== rootUri.fsPath) return false;
      this.chunks = j.chunks.map((c) => ({
        path: c.p,
        line: c.l,
        preview: c.pr,
        tf: new Map(c.t),
      }));
      this.indexedRoot = j.root;
      this.recomputeIdf();
      return true;
    } catch {
      return false;
    }
  }

  async save(context: vscode.ExtensionContext): Promise<void> {
    if (!this.indexedRoot || this.chunks.length === 0) return;
    const path = indexStorageUri(context, this.indexedRoot);
    const dir = vscode.Uri.joinPath(path, "..");
    await vscode.workspace.fs.createDirectory(dir);
    const payload = {
      v: INDEX_FORMAT_VERSION,
      root: this.indexedRoot,
      chunks: this.chunks.map((c) => ({
        p: c.path,
        l: c.line,
        pr: c.preview,
        t: [...c.tf.entries()],
      })),
    };
    await vscode.workspace.fs.writeFile(
      path,
      Buffer.from(JSON.stringify(payload), "utf8")
    );
  }

  async build(
    rootUri: vscode.Uri,
    opts: CodeIndexBuildOptions,
    token?: vscode.CancellationToken
  ): Promise<{ files: number; chunks: number }> {
    this.clear();
    this.indexedRoot = rootUri.fsPath;

    const pattern = new vscode.RelativePattern(rootUri, "**/*");
    const uris = await vscode.workspace.findFiles(
      pattern,
      EXCLUDE_GLOB,
      opts.maxFiles
    );

    let files = 0;
    for (const fileUri of uris) {
      if (token?.isCancellationRequested) break;
      if (fileUri.fsPath.replace(/\\/g, "/") === rootUri.fsPath.replace(/\\/g, "/")) {
        continue;
      }
      let rel = fileUri.fsPath.slice(rootUri.fsPath.length);
      rel = rel.replace(/^[\\/]+/, "").replace(/\\/g, "/");
      if (!rel) continue;

      const skipExt = [
        ".png",
        ".jpg",
        ".jpeg",
        ".gif",
        ".webp",
        ".ico",
        ".pdf",
        ".zip",
        ".gz",
        ".7z",
        ".exe",
        ".dll",
        ".so",
        ".dylib",
        ".woff",
        ".woff2",
        ".ttf",
        ".eot",
        ".mp4",
        ".mp3",
        ".wasm",
      ];
      const low = rel.toLowerCase();
      if (skipExt.some((e) => low.endsWith(e))) continue;

      try {
        const st = await vscode.workspace.fs.stat(fileUri);
        if (st.type !== vscode.FileType.File) continue;
        if (st.size > opts.maxFileBytes) continue;

        const buf = await vscode.workspace.fs.readFile(fileUri);
        if (buf.byteLength > opts.maxFileBytes) continue;
        const slice = buf.slice(0, Math.min(8192, buf.byteLength));
        if (slice.includes(0)) continue;

        const decoder = new TextDecoder("utf-8", { fatal: false });
        const text = decoder.decode(buf);
        if (!text.trim()) continue;

        const parts = chunkText(rel, text, opts.chunkChars);
        for (const part of parts) {
          const terms = tokenize(part.body);
          if (terms.length === 0) continue;
          const tf = new Map<string, number>();
          for (const t of terms) {
            tf.set(t, (tf.get(t) ?? 0) + 1);
          }
          this.chunks.push({
            path: rel,
            line: part.line,
            preview: previewOf(part.body),
            tf,
          });
        }
        files++;
      } catch {
        /* skip */
      }
    }

    this.recomputeIdf();
    return { files, chunks: this.chunks.length };
  }
}

export async function executeBearisSearches(
  manager: CodeIndexManager,
  context: vscode.ExtensionContext,
  rootUri: vscode.Uri,
  searches: ParsedSearch[],
  buildOpts: CodeIndexBuildOptions,
  topK: number,
  enabled: boolean
): Promise<{ text: string; searchQueries: number; searchErr: string[] }> {
  const searchErr: string[] = [];
  const sections: string[] = [];

  if (searches.length === 0) {
    return { text: "", searchQueries: 0, searchErr: [] };
  }

  if (!enabled) {
    searchErr.push("Code search is disabled (bearis.codeIndexEnabled).");
    return {
      text: "### Code search\n(disabled in settings)\n",
      searchQueries: searches.length,
      searchErr,
    };
  }

  if (manager.root && manager.root !== rootUri.fsPath) {
    manager.clear();
  }

  if (manager.root !== rootUri.fsPath || manager.chunkCount === 0) {
    const loaded = await manager.load(context, rootUri);
    if (!loaded || manager.chunkCount === 0) {
      try {
        await vscode.window.withProgress(
          {
            location: vscode.ProgressLocation.Notification,
            title: "Bearis: building code index (TF‑IDF)…",
            cancellable: true,
          },
          async (progress, cancel) => {
            progress.report({ increment: 0 });
            const r = await manager.build(rootUri, buildOpts, cancel);
            progress.report({ message: `${r.files} files, ${r.chunks} chunks` });
            await manager.save(context);
          }
        );
      } catch {
        searchErr.push("Index build was cancelled or failed.");
      }
    }
  }

  if (manager.chunkCount === 0) {
    searchErr.push("Code index is empty (no text files in range).");
    return {
      text: "### SEARCH\n(error: empty index)\n",
      searchQueries: searches.length,
      searchErr,
    };
  }

  const seen = new Set<string>();
  for (const s of searches) {
    const q = s.query.trim();
    if (!q) continue;
    const hits = manager.search(q, topK);
    const lines: string[] = [];
    lines.push(`Query: "${q}" (${hits.length} hits, TF‑IDF relevance)`);
    for (const h of hits) {
      const key = `${h.path}:${h.line}`;
      if (seen.has(key)) continue;
      seen.add(key);
      lines.push(
        `- **${h.path}:${h.line}** (score ${h.score.toFixed(3)})\n  ${h.snippet}`
      );
    }
    if (hits.length === 0) {
      lines.push("(no matches — try other keywords or Bearis: Refresh Code Index)");
    }
    sections.push(lines.join("\n"));
  }

  const text = sections.join("\n\n").trimEnd();
  return {
    text: text ? `### Code search (TF‑IDF)\n\n${text}` : "",
    searchQueries: searches.length,
    searchErr,
  };
}
