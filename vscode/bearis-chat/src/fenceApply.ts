import type { ParsedWrite } from "./agent";

/**
 * Map common fence languages to a sensible default filename (first occurrence).
 * Further occurrences get "-2", "-3", … before the extension.
 */
const LANG_DEFAULT_FILE: Record<string, string> = {
  html: "index.html",
  htm: "index.html",
  css: "styles.css",
  scss: "styles.scss",
  sass: "styles.sass",
  less: "styles.less",
  js: "script.js",
  javascript: "script.js",
  mjs: "script.mjs",
  cjs: "script.cjs",
  ts: "script.ts",
  typescript: "script.ts",
  tsx: "App.tsx",
  jsx: "App.jsx",
  vue: "App.vue",
  svelte: "App.svelte",
  json: "data.json",
  jsonc: "data.jsonc",
  md: "README.md",
  markdown: "README.md",
  py: "main.py",
  python: "main.py",
  rs: "main.rs",
  go: "main.go",
  java: "Main.java",
  c: "main.c",
  cpp: "main.cpp",
  cxx: "main.cpp",
  cs: "Program.cs",
  php: "index.php",
  rb: "main.rb",
  swift: "main.swift",
  kt: "Main.kt",
  kts: "main.kts",
  xml: "data.xml",
  yaml: "config.yaml",
  yml: "config.yml",
  toml: "config.toml",
  sh: "script.sh",
  bash: "script.sh",
  zsh: "script.zsh",
  ps1: "script.ps1",
  sql: "schema.sql",
  graphql: "schema.graphql",
  gql: "schema.graphql",
};

/** Not saved as project files (diagrams / prose). */
const SKIP_LANG = new Set([
  "",
  "mermaid",
  "plantuml",
  "diff",
  "text",
  "plaintext",
]);

function defaultFilenameForLang(lang: string): string {
  const l = lang.toLowerCase();
  return LANG_DEFAULT_FILE[l] ?? `snippet.${l || "txt"}`;
}

function filenameForOccurrence(lang: string, occurrence: number): string {
  const def = defaultFilenameForLang(lang);
  if (occurrence <= 1) return def;
  const dot = def.lastIndexOf(".");
  const stem = dot >= 0 ? def.slice(0, dot) : def;
  const ext = dot >= 0 ? def.slice(dot) : "";
  return `${stem}-${occurrence}${ext}`;
}

function normalizeFenceBody(body: string): string {
  return body.replace(/\r\n/g, "\n").replace(/\n+$/, "");
}

function inferLangFromBody(body: string, declared: string): string {
  const d = declared.toLowerCase();
  if (d && !SKIP_LANG.has(d)) return d;
  const t = body.trimStart();
  if (t.startsWith("<!DOCTYPE") || t.startsWith("<html")) return "html";
  if (t.startsWith("{") && (t.includes(`"`) || t.includes("'"))) return "json";
  return d;
}

/**
 * Extract ``` fenced blocks and turn them into writes with inferred paths.
 * Closing fence must start at beginning of a line (standard Markdown).
 */
export function extractFencedFilesAsWrites(text: string): ParsedWrite[] {
  const re = /^```[ \t]*([^\n\r]*)\r?\n([\s\S]*?)^```[ \t]*$/gm;
  const langCounts = new Map<string, number>();
  const out: ParsedWrite[] = [];
  let m: RegExpExecArray | null;

  while ((m = re.exec(text)) !== null) {
    const meta = (m[1] ?? "").trim();
    const parts = meta.split(/\s+/).filter(Boolean);
    const firstTok = parts[0] ?? "";

    let body = normalizeFenceBody(m[2] ?? "");
    if (!body.trim()) continue;

    if (
      firstTok &&
      /^[a-zA-Z0-9_./-]+\.[a-zA-Z][a-zA-Z0-9]*$/.test(firstTok) &&
      !firstTok.includes("..")
    ) {
      let rel = firstTok.replace(/^\.\//, "");
      if (!rel.startsWith("/")) {
        out.push({ relativePath: rel, content: body });
        continue;
      }
    }

    let lang = firstTok;
    lang = inferLangFromBody(body, lang);
    if (SKIP_LANG.has(lang.toLowerCase())) {
      if (lang.toLowerCase() === "text" || lang.toLowerCase() === "plaintext") {
        const t = body.trimStart();
        if (!(t.startsWith("<!DOCTYPE") || t.startsWith("<html"))) continue;
        lang = "html";
      } else continue;
    }

    const key = lang.toLowerCase();
    const n = (langCounts.get(key) ?? 0) + 1;
    langCounts.set(key, n);

    const relativePath = filenameForOccurrence(key, n);
    out.push({ relativePath, content: body });
  }

  return out;
}
