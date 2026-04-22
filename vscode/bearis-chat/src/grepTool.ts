import * as vscode from "vscode";
import { fileUriUnderRoot, type ParsedGrep } from "./agent";

const EXCLUDE_GLOB =
  "**/{node_modules,.git,.svn,.hg,.idea,dist,build,out,.next,target,__pycache__,Pods,.venv,venv}/**";

const BINARY_EXT = [
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

export interface GrepToolOptions {
  maxFiles: number;
  maxFileBytes: number;
  maxMatchesTotal: number;
  maxLineDisplayChars: number;
  maxPatternLength: number;
}

function normalizeSubpath(p: string): string {
  const t = p.trim().replace(/\\/g, "/");
  if (t === "" || t === ".") return "";
  return t.replace(/^\/+/, "");
}

function lineMatches(
  line: string,
  pattern: string,
  mode: "regex" | "fixed",
  rx: RegExp | null
): boolean {
  if (mode === "fixed") return line.includes(pattern);
  return rx !== null && rx.test(line);
}

export async function executeBearisGreps(
  rootUri: vscode.Uri,
  greps: ParsedGrep[],
  opts: GrepToolOptions
): Promise<{ text: string; grepErr: string[] }> {
  const grepErr: string[] = [];
  const sections: string[] = [];

  for (const g of greps) {
    const pattern = g.pattern.trim();
    if (!pattern) {
      grepErr.push("empty pattern");
      continue;
    }
    if (pattern.length > opts.maxPatternLength) {
      grepErr.push(`pattern too long (max ${opts.maxPatternLength})`);
      continue;
    }

    const sub = normalizeSubpath(g.subpath);
    const searchRoot =
      sub === "" ? rootUri : fileUriUnderRoot(rootUri, sub);
    if (!searchRoot) {
      grepErr.push(`invalid path: ${g.subpath}`);
      sections.push(`### GREP "${pattern.slice(0, 80)}"\n(error: bad path ${g.subpath})\n`);
      continue;
    }

    try {
      const st = await vscode.workspace.fs.stat(searchRoot);
      if (st.type !== vscode.FileType.Directory) {
        grepErr.push(`not a directory: ${g.subpath}`);
        sections.push(`### GREP "${pattern.slice(0, 80)}"\n(error: path is not a directory)\n`);
        continue;
      }
    } catch (e) {
      grepErr.push(`${g.subpath}: ${e instanceof Error ? e.message : String(e)}`);
      continue;
    }

    let rx: RegExp | null = null;
    if (g.mode === "regex") {
      try {
        rx = new RegExp(pattern);
      } catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        grepErr.push(`invalid regex: ${msg}`);
        sections.push(`### GREP "${pattern.slice(0, 80)}"\n(error: ${msg})\n`);
        continue;
      }
    }

    const glob = (g.glob || "**/*").trim() || "**/*";
    const patternRp = new vscode.RelativePattern(searchRoot, glob);
    const uris = await vscode.workspace.findFiles(
      patternRp,
      EXCLUDE_GLOB,
      opts.maxFiles
    );

    const hits: string[] = [];
    let matchCount = 0;

    for (const fileUri of uris) {
      if (matchCount >= opts.maxMatchesTotal) break;

      let rel = fileUri.fsPath.slice(rootUri.fsPath.length);
      rel = rel.replace(/^[\\/]+/, "").replace(/\\/g, "/");
      if (!rel) continue;

      const low = rel.toLowerCase();
      if (BINARY_EXT.some((e) => low.endsWith(e))) continue;

      try {
        const fst = await vscode.workspace.fs.stat(fileUri);
        if (fst.type !== vscode.FileType.File) continue;
        if (fst.size > opts.maxFileBytes) continue;

        const buf = await vscode.workspace.fs.readFile(fileUri);
        if (buf.byteLength > opts.maxFileBytes) continue;
        if (buf.slice(0, Math.min(4096, buf.byteLength)).includes(0)) continue;

        const text = new TextDecoder("utf-8", { fatal: false }).decode(buf);
        const lines = text.split(/\r?\n/);
        for (let li = 0; li < lines.length; li++) {
          if (matchCount >= opts.maxMatchesTotal) break;
          const line = lines[li];
          if (!lineMatches(line, pattern, g.mode, rx)) continue;
          matchCount++;
          const disp =
            line.length > opts.maxLineDisplayChars
              ? line.slice(0, opts.maxLineDisplayChars) + "…"
              : line;
          hits.push(`${rel}:${li + 1}: ${disp}`);
        }
      } catch {
        /* skip file */
      }
    }

    const modeNote = g.mode === "fixed" ? "fixed string" : "regex";
    const head = `### GREP pattern="${pattern.slice(0, 120)}" path="${g.subpath || "."}" glob="${glob}" (${modeNote})`;
    const body =
      hits.length > 0
        ? hits.join("\n")
        : "(no matches — try BEARIS_SEARCH for fuzzy keyword hits)";
    const tail =
      matchCount >= opts.maxMatchesTotal
        ? `\n… truncated at ${opts.maxMatchesTotal} matches`
        : "";
    sections.push(`${head}\n\`\`\`\n${body}${tail}\n\`\`\`\n`);
  }

  const text =
    sections.length > 0
      ? `### Ripgrep-style search (BEARIS_GREP)\n\n${sections.join("\n")}`.trimEnd()
      : "";

  return { text, grepErr };
}
