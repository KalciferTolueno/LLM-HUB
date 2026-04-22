import * as vscode from "vscode";
import {
  fileUriUnderRoot,
  type ParsedPatch,
  type PatchHunk,
} from "./agent";

export function mergePatchesByPath(patches: ParsedPatch[]): ParsedPatch[] {
  const map = new Map<string, ParsedPatch>();
  for (const p of patches) {
    const k = p.relativePath.trim();
    const cur = map.get(k);
    if (cur) cur.hunks.push(...p.hunks);
    else map.set(k, { relativePath: p.relativePath, hunks: [...p.hunks] });
  }
  return [...map.values()];
}

/** Dry-run patch application on in-memory text (same rules as apply). */
export function computePatchedText(
  original: string,
  hunks: PatchHunk[]
): { ok: true; text: string } | { ok: false; error: string } {
  let content = original.replace(/\r\n/g, "\n");
  for (let hi = 0; hi < hunks.length; hi++) {
    const h = hunks[hi];
    const s = h.search.replace(/\r\n/g, "\n");
    const r = h.replace.replace(/\r\n/g, "\n");
    const idx = content.indexOf(s);
    if (idx === -1) {
      return { ok: false, error: `hunk ${hi + 1} — search text not found` };
    }
    if (content.indexOf(s, idx + 1) !== -1) {
      return {
        ok: false,
        error: `hunk ${hi + 1} — search text appears more than once`,
      };
    }
    content = content.slice(0, idx) + r + content.slice(idx + s.length);
  }
  return { ok: true, text: content };
}

/**
 * Apply SEARCH/REPLACE hunks to existing files. Each hunk must match exactly once.
 */
export async function applyBearisPatches(
  rootUri: vscode.Uri,
  patches: ParsedPatch[]
): Promise<{ patched: string[]; errors: string[] }> {
  const merged = mergePatchesByPath(patches);
  const patched: string[] = [];
  const errors: string[] = [];

  for (const p of merged) {
    const rel = p.relativePath.trim();
    const target = fileUriUnderRoot(rootUri, rel);
    if (!target) {
      errors.push(`${rel}: invalid path or outside root`);
      continue;
    }

    try {
      await vscode.workspace.fs.stat(target);
    } catch {
      errors.push(
        `${rel}: file not found (BEARIS_PATCH only edits existing files; use BEARIS_WRITE for new files)`
      );
      continue;
    }

    try {
      const raw = await vscode.workspace.fs.readFile(target);
      let content = new TextDecoder("utf-8", { fatal: false })
        .decode(raw)
        .replace(/\r\n/g, "\n");

      const computed = computePatchedText(content, p.hunks);
      if (!computed.ok) {
        errors.push(`${rel}: ${computed.error}`);
        continue;
      }
      content = computed.text;

      await vscode.workspace.fs.writeFile(target, Buffer.from(content, "utf8"));
      patched.push(rel);
    } catch (e) {
      errors.push(
        `${rel}: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  return { patched, errors };
}
