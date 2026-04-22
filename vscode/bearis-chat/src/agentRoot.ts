import * as path from "path";
import * as vscode from "vscode";

const AGENT_ROOT_KEY = "bearis.agentRootUri";

export type AgentRootInfo = {
  uri: vscode.Uri;
  /** Short name for prompts (workspace name or folder basename). */
  label: string;
  fromWorkspace: boolean;
};

/**
 * Folder where agent writes go: first workspace folder if any, else a user-picked path
 * persisted in global state (works when no workspace is open).
 */
export function getAgentRoot(context: vscode.ExtensionContext): AgentRootInfo | undefined {
  const ws = vscode.workspace.workspaceFolders?.[0];
  if (ws) {
    return { uri: ws.uri, label: ws.name, fromWorkspace: true };
  }
  const saved = context.globalState.get<string>(AGENT_ROOT_KEY);
  if (!saved) return undefined;
  const uri = vscode.Uri.file(saved);
  const trimmed = saved.replace(/[\\/]+$/, "") || saved;
  const label = path.basename(trimmed) || trimmed;
  return { uri, label, fromWorkspace: false };
}

export async function pickAndStoreAgentRoot(
  context: vscode.ExtensionContext
): Promise<AgentRootInfo | undefined> {
  const uris = await vscode.window.showOpenDialog({
    canSelectFiles: false,
    canSelectFolders: true,
    canSelectMany: false,
    title: "Bearis: choose folder for agent writes",
    openLabel: "Use this folder",
  });
  if (!uris?.[0]) return undefined;
  await context.globalState.update(AGENT_ROOT_KEY, uris[0].fsPath);
  const p = uris[0].fsPath;
  const trimmed = p.replace(/[\\/]+$/, "") || p;
  return {
    uri: uris[0],
    label: path.basename(trimmed) || trimmed,
    fromWorkspace: false,
  };
}

/** If nothing is configured yet, prompt once for a folder (when writes are needed). */
export async function ensureAgentRootForWrites(
  context: vscode.ExtensionContext
): Promise<AgentRootInfo | undefined> {
  const existing = getAgentRoot(context);
  if (existing) return existing;
  const picked = await pickAndStoreAgentRoot(context);
  return picked ?? undefined;
}

export async function clearStoredAgentRoot(context: vscode.ExtensionContext): Promise<void> {
  await context.globalState.update(AGENT_ROOT_KEY, undefined);
}
