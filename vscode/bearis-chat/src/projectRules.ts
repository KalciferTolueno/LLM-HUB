import * as vscode from "vscode";

const RULE_FILES = ["bearis.md", ".bearisrules"] as const;

/**
 * Load optional project rules from the agent root (like .cursorrules).
 */
export async function loadBearisProjectRules(
  rootUri: vscode.Uri,
  maxChars: number
): Promise<string> {
  const cap = Math.min(Math.max(maxChars, 500), 50_000);
  const parts: string[] = [];
  for (const name of RULE_FILES) {
    const u = vscode.Uri.joinPath(rootUri, name);
    try {
      const raw = await vscode.workspace.fs.readFile(u);
      const t = new TextDecoder("utf-8", { fatal: false }).decode(raw).trim();
      if (t) parts.push(`### ${name}\n${t}`);
    } catch {
      /* not present */
    }
  }
  const full = parts.join("\n\n");
  if (!full) return "";
  if (full.length <= cap) return full;
  return full.slice(0, cap) + "\n\n… [rules truncated]";
}
