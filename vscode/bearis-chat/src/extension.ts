import * as vscode from "vscode";
import {
  BEARIS_DEFAULT_WRITE_TOOLS,
  streamChatCompletion,
  type ChatMessage,
  type StreamToolCall,
} from "./api";
import {
  applyBearisWrites,
  buildActiveFileAppendix,
  buildAgentSystemPrompt,
  buildAgentSystemPromptProvisional,
  buildStrictOpenAiAgentSystemPrompt,
  buildStrictOpenAiAgentSystemPromptProvisional,
  executeBearisLists,
  executeBearisReads,
  parseBearisGrepBlocks,
  parseBearisListBlocks,
  parseBearisPatchBlocks,
  parseBearisReadBlocks,
  parseBearisSearchBlocks,
  parseBearisWriteBlocks,
  stripBearisGrepBlocks,
  stripBearisListBlocks,
  stripBearisPatchBlocks,
  stripBearisReadBlocks,
  stripBearisSearchBlocks,
  stripBearisWriteBlocks,
  stripToolMarkersForHistory,
  type ParsedPatch,
  type ParsedWrite,
} from "./agent";
import {
  clearStoredAgentRoot,
  ensureAgentRootForWrites,
  getAgentRoot,
  pickAndStoreAgentRoot,
  type AgentRootInfo,
} from "./agentRoot";
import { extractFencedFilesAsWrites } from "./fenceApply";
import { CodeIndexManager, executeBearisSearches } from "./codeIndex";
import { executeBearisGreps } from "./grepTool";
import { loadBearisProjectRules } from "./projectRules";
import { applyBearisPatches } from "./patchApply";
import { BearisDiffPreview } from "./bearisDiffPreview";
import {
  hasRunawayRepetition,
  truncateIfRepetitive,
} from "./streamRepetition";

const VIEW_TYPE = "bearis.chatPanel";

const codeIndexManager = new CodeIndexManager();
const bearisDiffPreview = new BearisDiffPreview();

function getApiBase(): string {
  const cfg = vscode.workspace.getConfiguration("bearis");
  const url = cfg.get<string>("apiBaseUrl", "http://192.168.0.2:8080/v1");
  return url.replace(/\/$/, "");
}

function getNonce(): string {
  let t = "";
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  for (let i = 0; i < 32; i++) {
    t += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return t;
}

function webviewHtml(nonce: string): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Bearis Chat</title>
  <style>
    :root {
      --bg: var(--vscode-editor-background);
      --fg: var(--vscode-editor-foreground);
      --dim: var(--vscode-descriptionForeground);
      --accent: var(--vscode-button-background);
      --accent-fg: var(--vscode-button-foreground);
      --border: var(--vscode-widget-border, #333);
    }
    body {
      margin: 0;
      padding: 12px;
      font-family: var(--vscode-font-family);
      font-size: var(--vscode-font-size);
      color: var(--fg);
      background: var(--bg);
      display: flex;
      flex-direction: column;
      height: 100vh;
      box-sizing: border-box;
    }
    h1 { font-size: 1.1rem; margin: 0 0 8px; font-weight: 600; }
    .hint { color: var(--dim); font-size: 0.85rem; margin-bottom: 12px; }
    #log {
      flex: 1;
      overflow-y: auto;
      border: 1px solid var(--border);
      border-radius: 6px;
      padding: 10px;
      margin-bottom: 10px;
      white-space: pre-wrap;
      word-break: break-word;
    }
    .msg { margin-bottom: 12px; line-height: 1.45; }
    .msg .role { font-size: 0.75rem; color: var(--dim); text-transform: uppercase; margin-bottom: 4px; }
    .msg.user .bubble { background: var(--vscode-input-background); padding: 8px 10px; border-radius: 8px; }
    .msg.assistant .bubble { padding: 2px 0; }
    .toolrow {
      font-size: 0.85rem;
      color: var(--dim);
      margin: 0 0 8px 2px;
      display: flex;
      align-items: center;
      gap: 8px;
      cursor: pointer;
      user-select: none;
    }
    .toolrow input { cursor: pointer; }
    .row { display: flex; gap: 8px; align-items: flex-end; }
    #input {
      flex: 1;
      min-height: 72px;
      resize: vertical;
      padding: 8px;
      border: 1px solid var(--border);
      border-radius: 6px;
      background: var(--vscode-input-background);
      color: var(--fg);
      font-family: inherit;
      font-size: inherit;
    }
    button {
      padding: 8px 16px;
      background: var(--accent);
      color: var(--accent-fg);
      border: none;
      border-radius: 6px;
      cursor: pointer;
      font-weight: 600;
    }
    button:disabled { opacity: 0.5; cursor: not-allowed; }
    .err { color: var(--vscode-errorForeground); margin-top: 8px; font-size: 0.9rem; }
    .agent {
      font-size: 0.82rem;
      color: var(--dim);
      margin: -4px 0 12px 2px;
      padding: 6px 8px;
      border-left: 3px solid var(--accent);
      background: var(--vscode-editor-inactiveSelectionBackground, rgba(128,128,128,0.15));
    }
  </style>
</head>
<body>
  <h1>Bearis</h1>
    <div class="hint">Phone: Bearis + local API. Default is Continue-like: <code>bearis.agentMode</code> off → requests are chat-only (no <code>tools</code> in JSON). Turn agent mode on for file edits; with <code>bearis.writeToolsPolicy</code> = fileIntent, write tools are sent only when the message looks like code/file work, or check <b>File tools</b> for this send. <code>bearis.strictOpenAiAgent</code> (on) uses OpenAI <code>tool_calls</code>; off = legacy BEARIS_* blocks.</div>
  <div id="log"></div>
  <label class="toolrow"><input type="checkbox" id="fileTools" /><span>File tools (this message; requires agent mode on)</span></label>
  <div class="row">
    <textarea id="input" placeholder="Message… (Enter to send, Shift+Enter newline)"></textarea>
    <button id="send">Send</button>
  </div>
  <div id="err" class="err"></div>
  <script nonce="${nonce}">
    (function () {
      const vscode = acquireVsCodeApi();
      const log = document.getElementById("log");
      const input = document.getElementById("input");
      const sendBtn = document.getElementById("send");
      const fileToolsEl = document.getElementById("fileTools");
      const errEl = document.getElementById("err");

      function clearErr() { errEl.textContent = ""; }

      function appendMessage(role, text) {
        const wrap = document.createElement("div");
        wrap.className = "msg " + role;
        const r = document.createElement("div");
        r.className = "role";
        r.textContent = role;
        const bubble = document.createElement("div");
        bubble.className = "bubble";
        bubble.textContent = text;
        wrap.appendChild(r);
        wrap.appendChild(bubble);
        log.appendChild(wrap);
        log.scrollTop = log.scrollHeight;
        return bubble;
      }

      let streamingBubble = null;

      function setBusy(b) {
        sendBtn.disabled = b;
        input.disabled = b;
      }

      sendBtn.addEventListener("click", () => doSend());
      input.addEventListener("keydown", (e) => {
        if (e.key === "Enter" && !e.shiftKey) {
          e.preventDefault();
          doSend();
        }
      });

      function doSend() {
        const text = input.value.trim();
        if (!text) return;
        clearErr();
        input.value = "";
        appendMessage("user", text);
        streamingBubble = appendMessage("assistant", "");
        setBusy(true);
        vscode.postMessage({
          type: "send",
          text: text,
          useFileTools: fileToolsEl && fileToolsEl.checked,
        });
      }

      window.addEventListener("message", (event) => {
        const m = event.data;
        if (m.type === "delta" && streamingBubble) {
          streamingBubble.textContent += m.text;
          log.scrollTop = log.scrollHeight;
        } else if (m.type === "error") {
          errEl.textContent = m.message || "Error";
          if (streamingBubble && !streamingBubble.textContent) {
            streamingBubble.textContent = "(no response)";
          }
          setBusy(false);
          streamingBubble = null;
        } else if (m.type === "agent") {
          const pt = m.patched && m.patched.length ? "Patched: " + m.patched.join(", ") : "";
          const rd = m.reads && m.reads.length ? "Read: " + m.reads.join(", ") : "";
          const ls = m.listed && m.listed.length ? "Listed: " + m.listed.join(", ") : "";
          const w = m.written && m.written.length ? "Wrote: " + m.written.join(", ") : "";
          const er = m.errors && m.errors.length ? m.errors.join("; ") : "";
          const inf = m.info ? String(m.info) : "";
          const parts = [pt, ls, rd, w, er, inf].filter(Boolean);
          if (parts.length) {
            const wrap = document.createElement("div");
            wrap.className = "agent";
            wrap.textContent = parts.join(" · ");
            log.appendChild(wrap);
            log.scrollTop = log.scrollHeight;
          }
        } else if (m.type === "done") {
          setBusy(false);
          streamingBubble = null;
        }
      });
    })();
  </script>
</body>
</html>`;
}

function confirmFileChanges(
  writes: ParsedWrite[],
  patches: ParsedPatch[],
  root: AgentRootInfo,
  opts?: { inferredFromMarkdown?: boolean }
) {
  return bearisDiffPreview.confirmChanges(root, writes, patches, opts);
}

function buildSystemMessages(
  agentOn: boolean,
  root: AgentRootInfo | undefined,
  workspaceRulesAppend: string
): ChatMessage[] {
  if (!agentOn) {
    return [{ role: "system", content: "You are a helpful assistant." }];
  }
  let content: string;
  if (root) {
    content = buildAgentSystemPrompt(root.label);
  } else {
    content = buildAgentSystemPromptProvisional();
  }
  if (workspaceRulesAppend) {
    content += workspaceRulesAppend;
  }
  return [{ role: "system", content }];
}

function buildStrictSystemMessages(
  agentOn: boolean,
  root: AgentRootInfo | undefined,
  workspaceRulesAppend: string
): ChatMessage[] {
  if (!agentOn) {
    return [{ role: "system", content: "You are a helpful assistant." }];
  }
  let content: string;
  if (root) {
    content = buildStrictOpenAiAgentSystemPrompt(root.label);
  } else {
    content = buildStrictOpenAiAgentSystemPromptProvisional();
  }
  if (workspaceRulesAppend) {
    content += workspaceRulesAppend;
  }
  return [{ role: "system", content }];
}

function formatToolExecutionUserMessage(written: string[], errors: string[]): string {
  const lines = ["[Tool execution]"];
  if (written.length) {
    lines.push(`Wrote: ${written.join(", ")}`);
  }
  if (errors.length) {
    lines.push(`Errors: ${errors.join("; ")}`);
  }
  if (written.length === 0 && errors.length === 0) {
    lines.push("(no file changes)");
  }
  return lines.join("\n");
}

/** When false, skip write tools and often the active-file appendix so greetings stay conversational. */
function messageSuggestsFileOrCodeWork(text: string): boolean {
  const t = text.trim();
  if (!t) return false;
  if (/```/.test(t)) return true;
  if (/\b(bearis_(read|write|list|patch|search|grep)|<<<\s*BEARIS_)/i.test(t)) return true;
  if (
    /\b[\w./-]+\.(?:py|ts|tsx|js|jsx|mjs|cjs|kt|kts|java|go|rs|cpp|cc|cxx|c|h|hpp|cs|fs|vb|rb|php|swift|scala|html|htm|css|scss|sass|less|json|ya?ml|toml|xml|md|mdx|sql|sh|bash|zsh|ps1|bat|cmd|gradle|plist)\b/i.test(
      t
    )
  ) {
    return true;
  }
  if (/\b(package\.json|requirements\.txt|dockerfile|makefile|cargo\.toml|go\.mod|pom\.xml)\b/i.test(t)) {
    return true;
  }
  const lower = t.toLowerCase();
  if (
    /\b(create|creates|created|write|writes|implement|implements|adding|add|fix|fixes|fixed|patch|patches|refactor|save|saves|generate|generates|scaffold|edit|edits|replace|deletes|delete|remove|removes|change|changes|update|updates)\b/.test(
      lower
    )
  ) {
    return true;
  }
  if (
    /\b(crea|crear|cread|escrib|implementa|implementar|añade|anade|agrega|arregla|arreglar|guarda|guardar|genera|generar|modifica|modificar|cambia|cambiar|borra|borrar|elimina|eliminar|quita|quitar|reemplaz|actualiza|actualizar)\b/.test(
      lower
    )
  ) {
    return true;
  }
  if (
    /\b(file|files|folder|folders|directory|directories|archivo|archivos|carpeta|carpetas|directorio|repo|repository|codebase|proyecto|project)\b/.test(
      lower
    )
  ) {
    return true;
  }
  if (
    /\b(function|functions|class|classes|method|methods|bug|bugs|error|errors|exception|traceback|compile|compiler|build|lint|test|tests|unittest)\b/.test(
      lower
    )
  ) {
    return true;
  }
  if (/\b(funci[oó]n|funciones|clase|clases|m[eé]todo|m[eé]todos)\b/.test(lower)) {
    return true;
  }
  return false;
}

function dedupeWritesByPath(writes: ParsedWrite[]): {
  writes: ParsedWrite[];
  duplicated: string[];
} {
  const byPath = new Map<string, ParsedWrite>();
  const duplicated = new Set<string>();
  for (const w of writes) {
    const key = w.relativePath.trim();
    if (byPath.has(key)) {
      duplicated.add(key);
    }
    byPath.set(key, w);
  }
  return { writes: Array.from(byPath.values()), duplicated: Array.from(duplicated) };
}

/**
 * Gemma / LiteRT style seen when the phone API streams without OpenAI tool_calls JSON, e.g.:
 * <|tool_call|>call:TOOL_NAME:create_new_file BEGIN_ARG: filepath index.html END_ARG BEGIN_ARG: contents
 * (optional UI line "Apply") then raw file body.
 */
function parseGemmaBeginArgCreateFileToolCalls(text: string): ParsedWrite[] {
  const out: ParsedWrite[] = [];
  const re =
    /<\|tool_call\>\s*call:\s*[^:]+?:create_new_file\s+BEGIN_ARG:\s*filepath\s+(\S+)\s+END_ARG\s+BEGIN_ARG:\s*contents\s*(?:\r?\n)*(?:Apply\s*(?:\r?\n)+)?([\s\S]+)/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    const relativePath = m[1].trim().replace(/\\/g, "/");
    const content = (m[2] ?? "").replace(/\r\n/g, "\n").trimEnd();
    if (!relativePath || relativePath.includes("..")) continue;
    out.push({ relativePath, content });
  }
  return out;
}

function parseLegacyCreateFileToolCalls(text: string): {
  writes: ParsedWrite[];
  malformed: boolean;
} {
  const writes: ParsedWrite[] = [];
  const re =
    /<\|tool_call\>\s*call:create_new_file\{filepath:<\|"\|>([^<]+)<\|"\|>,contents:<\|"\|>([\s\S]*?)<\|"\|>\}/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    const relativePath = m[1].trim().replace(/\\/g, "/");
    const content = (m[2] ?? "").replace(/\r\n/g, "\n");
    if (!relativePath || relativePath.includes("..")) continue;
    writes.push({ relativePath, content });
  }
  for (const w of parseGemmaBeginArgCreateFileToolCalls(text)) {
    writes.push(w);
  }
  const mentionsLegacy =
    text.includes("call:create_new_file") ||
    /<\|tool_call\>[\s\S]*?:create_new_file/i.test(text);
  const malformed = mentionsLegacy && writes.length === 0;
  return { writes, malformed };
}

function parseOpenAiToolCallWrites(toolCalls: StreamToolCall[]): {
  writes: ParsedWrite[];
  malformed: boolean;
} {
  const writes: ParsedWrite[] = [];
  let malformed = false;
  for (const tc of toolCalls) {
    const name = tc.name.trim().toLowerCase();
    if (!["create_new_file", "create_file", "write_file", "save_file"].includes(name)) {
      continue;
    }
    try {
      const raw = JSON.parse(tc.arguments || "{}") as Record<string, unknown>;
      const candidatePath =
        (typeof raw.filepath === "string" && raw.filepath) ||
        (typeof raw.path === "string" && raw.path) ||
        (typeof raw.file === "string" && raw.file) ||
        "";
      const candidateContent =
        (typeof raw.contents === "string" && raw.contents) ||
        (typeof raw.content === "string" && raw.content) ||
        (typeof raw.text === "string" && raw.text) ||
        "";
      const relativePath = candidatePath.trim().replace(/\\/g, "/");
      if (!relativePath || relativePath.includes("..")) {
        malformed = true;
        continue;
      }
      writes.push({
        relativePath,
        content: candidateContent.replace(/\r\n/g, "\n"),
      });
    } catch {
      malformed = true;
    }
  }
  return { writes, malformed };
}

function hasLegacySamplingOverrides(): boolean {
  const cfg = vscode.workspace.getConfiguration("bearis");
  const keys = [
    "agentTemperature",
    "agentTopP",
    "agentMaxTokens",
    "chatTemperature",
    "chatTopP",
    "chatMaxTokens",
  ] as const;
  return keys.some((k) => {
    const inspected = cfg.inspect<number>(k);
    return (
      inspected?.globalValue !== undefined ||
      inspected?.workspaceValue !== undefined ||
      inspected?.workspaceFolderValue !== undefined
    );
  });
}

export function activate(context: vscode.ExtensionContext) {
  bearisDiffPreview.register(context);
  if (hasLegacySamplingOverrides()) {
    void vscode.window.showInformationMessage(
      "Bearis sampling is now managed on your phone (Chat -> Model settings / Local API defaults). Extension sampling settings were removed.",
    );
  }
  context.subscriptions.push(
    vscode.commands.registerCommand("bearis.setAgentFolder", async () => {
      const picked = await pickAndStoreAgentRoot(context);
      if (picked) {
        vscode.window.showInformationMessage(
          `Bearis will write files under: ${picked.uri.fsPath}`
        );
      }
    }),
    vscode.commands.registerCommand("bearis.clearAgentFolder", async () => {
      await clearStoredAgentRoot(context);
      vscode.window.showInformationMessage(
        "Saved Bearis agent folder cleared (workspace folder still used when a workspace is open)."
      );
    }),
    vscode.commands.registerCommand("bearis.refreshCodeIndex", async () => {
      const root = getAgentRoot(context)?.uri;
      const cfg = vscode.workspace.getConfiguration("bearis");
      if (!cfg.get<boolean>("codeIndexEnabled", true)) {
        vscode.window.showWarningMessage(
          "Bearis code index is disabled (bearis.codeIndexEnabled)."
        );
        return;
      }
      if (!root) {
        vscode.window.showWarningMessage(
          "Open a workspace or set Bearis: Set Agent Folder… before refreshing the index."
        );
        return;
      }
      try {
        await vscode.window.withProgress(
          {
            location: vscode.ProgressLocation.Notification,
            title: "Bearis: refreshing code index…",
            cancellable: true,
          },
          async (progress, cancel) => {
            progress.report({ message: "Scanning files…" });
            const r = await codeIndexManager.build(
              root,
              {
                maxFiles: Math.min(10_000, Math.max(100, cfg.get<number>("codeIndexMaxFiles", 2500))),
                maxFileBytes: Math.min(
                  2_000_000,
                  Math.max(4096, cfg.get<number>("codeIndexMaxFileBytes", 400_000))
                ),
                chunkChars: Math.min(
                  8000,
                  Math.max(500, cfg.get<number>("codeIndexChunkChars", 2000))
                ),
              },
              cancel
            );
            progress.report({
              message: `${r.files} files, ${r.chunks} chunks (TF‑IDF)`,
            });
            await codeIndexManager.save(context);
          }
        );
        vscode.window.showInformationMessage(
          `Bearis code index ready: ${codeIndexManager.chunkCount} chunks.`
        );
      } catch {
        vscode.window.showErrorMessage("Bearis code index refresh failed or was cancelled.");
      }
    }),
    vscode.commands.registerCommand("bearis.openChat", () => {
      const panel = vscode.window.createWebviewPanel(
        VIEW_TYPE,
        "Bearis Chat",
        vscode.ViewColumn.Beside,
        {
          enableScripts: true,
          retainContextWhenHidden: true,
        }
      );

      const nonce = getNonce();
      panel.webview.html = webviewHtml(nonce);

      const history: ChatMessage[] = [];

      panel.webview.onDidReceiveMessage(
        async (msg: { type: string; text?: string; useFileTools?: boolean }) => {
          if (msg.type !== "send" || !msg.text) return;

          const userText = msg.text.trim();
          const cfg = vscode.workspace.getConfiguration("bearis");
          const agentOn = cfg.get<boolean>("agentMode", false);
          const rootForPrompt = getAgentRoot(context);
          const includeFile = cfg.get<boolean>("includeActiveFile", true);
          const maxChars = cfg.get<number>("maxActiveFileChars", 12000);
          const cap =
            maxChars <= 0 ? 300_000 : Math.min(Math.max(maxChars, 1), 500_000);

          const fileTaskLikely = messageSuggestsFileOrCodeWork(userText);
          const writeToolsPolicy = cfg.get<string>("writeToolsPolicy", "fileIntent");
          const forceWriteTools = msg.useFileTools === true;
          const offerWriteToolsThisTurn =
            agentOn &&
            (writeToolsPolicy === "everyMessage" ||
              fileTaskLikely ||
              forceWriteTools);
          const includeActiveThisTurn =
            includeFile &&
            rootForPrompt &&
            (offerWriteToolsThisTurn || userText.length >= 160);

          let userPayload = userText;
          if (includeActiveThisTurn) {
            userPayload = userText + buildActiveFileAppendix(rootForPrompt!.uri, cap);
          }

          history.push({ role: "user", content: userPayload });

          const base = getApiBase();
          try {
            let workspaceRulesAppend = "";
            if (
              agentOn &&
              rootForPrompt &&
              cfg.get<boolean>("injectProjectRules", true)
            ) {
              const rulesRaw = await loadBearisProjectRules(
                rootForPrompt.uri,
                cfg.get<number>("projectRulesMaxChars", 8000)
              );
              if (rulesRaw) {
                workspaceRulesAppend = `\n\n---\n## Project rules (bearis.md / .bearisrules)\n${rulesRaw}`;
              }
            }

            const strictOpenAiAgent = cfg.get<boolean>("strictOpenAiAgent", true);
            if (strictOpenAiAgent) {
              const maxStepsStrict = Math.min(
                20,
                Math.max(1, cfg.get<number>("maxAgentSteps", 5))
              );

              const runOneStream = async (messages: ChatMessage[], withWriteTools: boolean) => {
                let roundText = "";
                let roundToolCalls: StreamToolCall[] = [];
                const abortRound = new AbortController();
                let abortedForRepetition = false;
                try {
                  await streamChatCompletion(
                    base,
                    messages,
                    (delta) => {
                      roundText += delta;
                      if (hasRunawayRepetition(roundText)) {
                        abortedForRepetition = true;
                        abortRound.abort();
                        return;
                      }
                      panel.webview.postMessage({ type: "delta", text: delta });
                    },
                    {
                      signal: abortRound.signal,
                      onToolCalls: (calls) => {
                        roundToolCalls = calls;
                      },
                      ...(withWriteTools
                        ? { tools: BEARIS_DEFAULT_WRITE_TOOLS, tool_choice: "auto" as const }
                        : {}),
                    }
                  );
                } catch (e) {
                  const aborted =
                    (e instanceof Error && e.name === "AbortError") ||
                    (typeof DOMException !== "undefined" &&
                      e instanceof DOMException &&
                      e.name === "AbortError");
                  if (!aborted || !abortedForRepetition) {
                    throw e;
                  }
                }
                roundText = truncateIfRepetitive(roundText);
                if (abortedForRepetition) {
                  panel.webview.postMessage({
                    type: "delta",
                    text: "\n\n*[Bearis: detenido — el modelo repetía el mismo texto. Prueba otro modelo, baja la temperatura o acorta el prompt.]*\n",
                  });
                }
                return { roundText, roundToolCalls };
              };

              if (!agentOn) {
                const messages: ChatMessage[] = [
                  { role: "system", content: "You are a helpful assistant." },
                  ...history,
                ];
                const { roundText } = await runOneStream(messages, false);
                history.push({
                  role: "assistant",
                  content: stripToolMarkersForHistory(roundText),
                });
              } else if (!offerWriteToolsThisTurn) {
                const messages: ChatMessage[] = [
                  {
                    role: "system",
                    content:
                      "You are a helpful assistant in VS Code. Reply conversationally. Do not call tools or propose creating or editing files unless the user clearly asked for code, scripts, or project changes.",
                  },
                  ...history,
                ];
                const { roundText } = await runOneStream(messages, false);
                history.push({
                  role: "assistant",
                  content: stripToolMarkersForHistory(roundText),
                });
              } else {
                for (let step = 0; step < maxStepsStrict; step++) {
                  const messages: ChatMessage[] = [
                    ...buildStrictSystemMessages(agentOn, rootForPrompt, workspaceRulesAppend),
                    ...history,
                  ];
                  const { roundText, roundToolCalls } = await runOneStream(messages, true);

                  if (roundToolCalls.length === 0) {
                    history.push({
                      role: "assistant",
                      content: stripToolMarkersForHistory(roundText),
                    });
                    break;
                  }

                  history.push({
                    role: "assistant",
                    content: roundText.trimEnd(),
                  });

                  const parsedOpenAi = parseOpenAiToolCallWrites(roundToolCalls);
                  if (parsedOpenAi.writes.length === 0) {
                    const errMsg = parsedOpenAi.malformed
                      ? "The model returned file tool calls with invalid or incomplete JSON arguments."
                      : "The model returned tool_calls that were not create_new_file / write_file (or paths were rejected).";
                    panel.webview.postMessage({
                      type: "agent",
                      written: [],
                      patched: [],
                      errors: [errMsg],
                    });
                    history.push({
                      role: "user",
                      content: `[Tool execution]\n${errMsg}`,
                    });
                    if (step === maxStepsStrict - 1) {
                      panel.webview.postMessage({
                        type: "delta",
                        text: `\n\n*[Bearis: stopped at bearis.maxAgentSteps (${maxStepsStrict}).]*\n`,
                      });
                    }
                    continue;
                  }

                  const dedupedWrites = dedupeWritesByPath(parsedOpenAi.writes);
                  let writes = dedupedWrites.writes;
                  if (dedupedWrites.duplicated.length > 0) {
                    panel.webview.postMessage({
                      type: "agent",
                      written: [],
                      patched: [],
                      errors: [
                        `Ignored duplicate write tool calls for: ${dedupedWrites.duplicated.join(", ")}.`,
                      ],
                    });
                  }

                  const rootForWrite =
                    (await ensureAgentRootForWrites(context)) ?? undefined;
                  if (!rootForWrite) {
                    const errMsg =
                      "No folder selected for writes. Use “Bearis: Set Agent Folder…” or open a workspace folder.";
                    panel.webview.postMessage({
                      type: "agent",
                      written: [],
                      patched: [],
                      errors: [errMsg],
                    });
                    history.push({
                      role: "user",
                      content: formatToolExecutionUserMessage([], [errMsg]),
                    });
                    break;
                  }

                  const confirmed = await confirmFileChanges(writes, [], rootForWrite, {
                    inferredFromMarkdown: false,
                  });
                  if (!confirmed.ok) {
                    const errMsg =
                      confirmed.reason === "validation"
                        ? "Could not show diff preview (validation failed)."
                        : "Changes were not applied (you cancelled or declined).";
                    panel.webview.postMessage({
                      type: "agent",
                      written: [],
                      patched: [],
                      errors: [errMsg],
                    });
                    history.push({
                      role: "user",
                      content: formatToolExecutionUserMessage([], [errMsg]),
                    });
                    break;
                  }

                  const writeResult = await applyBearisWrites(rootForWrite.uri, writes);
                  panel.webview.postMessage({
                    type: "agent",
                    written: writeResult.written,
                    patched: [],
                    errors: writeResult.errors,
                  });
                  history.push({
                    role: "user",
                    content: formatToolExecutionUserMessage(
                      writeResult.written,
                      writeResult.errors
                    ),
                  });

                  if (step === maxStepsStrict - 1) {
                    panel.webview.postMessage({
                      type: "delta",
                      text: `\n\n*[Bearis: stopped at bearis.maxAgentSteps (${maxStepsStrict}).]*\n`,
                    });
                    break;
                  }
                }
              }

              panel.webview.postMessage({ type: "done" });
              return;
            }

            const maxSteps = Math.min(
              20,
              Math.max(1, cfg.get<number>("maxAgentSteps", 5))
            );
            const maxReadChars = cfg.get<number>("maxReadFileChars", 150_000);
            const listMaxDepth = cfg.get<number>("listMaxDepth", 4);
            const listMaxEntries = cfg.get<number>("listMaxEntries", 400);
            const codeIndexBuild = {
              maxFiles: Math.min(
                10_000,
                Math.max(100, cfg.get<number>("codeIndexMaxFiles", 2500))
              ),
              maxFileBytes: Math.min(
                2_000_000,
                Math.max(4096, cfg.get<number>("codeIndexMaxFileBytes", 400_000))
              ),
              chunkChars: Math.min(
                8000,
                Math.max(500, cfg.get<number>("codeIndexChunkChars", 2000))
              ),
            };
            const searchTopK = Math.min(
              50,
              Math.max(3, cfg.get<number>("searchMaxResults", 15))
            );
            const grepOpts = {
              maxFiles: Math.min(
                5000,
                Math.max(50, cfg.get<number>("grepMaxFiles", 800))
              ),
              maxFileBytes: Math.min(
                2_000_000,
                Math.max(4096, cfg.get<number>("grepMaxFileBytes", 400_000))
              ),
              maxMatchesTotal: Math.min(
                500,
                Math.max(10, cfg.get<number>("grepMaxMatches", 100))
              ),
              maxLineDisplayChars: Math.min(
                2000,
                Math.max(80, cfg.get<number>("grepMaxLineChars", 400))
              ),
              maxPatternLength: Math.min(2000, Math.max(32, cfg.get<number>("grepMaxPatternLength", 512))),
            };

            if (agentOn && !offerWriteToolsThisTurn) {
              const messages: ChatMessage[] = [
                {
                  role: "system",
                  content:
                    "You are a helpful assistant in VS Code. Reply conversationally. Do not use BEARIS_READ, BEARIS_LIST, BEARIS_SEARCH, BEARIS_GREP, BEARIS_WRITE, or BEARIS_PATCH unless the user clearly asked for project or file changes.",
                },
                ...history,
              ];
              let roundText = "";
              const abortRound = new AbortController();
              let abortedForRepetition = false;
              try {
                await streamChatCompletion(
                  base,
                  messages,
                  (delta) => {
                    roundText += delta;
                    if (hasRunawayRepetition(roundText)) {
                      abortedForRepetition = true;
                      abortRound.abort();
                      return;
                    }
                    panel.webview.postMessage({ type: "delta", text: delta });
                  },
                  { signal: abortRound.signal }
                );
              } catch (e) {
                const aborted =
                  (e instanceof Error && e.name === "AbortError") ||
                  (typeof DOMException !== "undefined" &&
                    e instanceof DOMException &&
                    e.name === "AbortError");
                if (!aborted || !abortedForRepetition) {
                  throw e;
                }
              }
              roundText = truncateIfRepetitive(roundText);
              if (abortedForRepetition) {
                panel.webview.postMessage({
                  type: "delta",
                  text: "\n\n*[Bearis: detenido — el modelo repetía el mismo texto. Prueba otro modelo, baja la temperatura o acorta el prompt.]*\n",
                });
              }
              history.push({
                role: "assistant",
                content: stripToolMarkersForHistory(roundText),
              });
              panel.webview.postMessage({ type: "done" });
              return;
            }

            let lastRoundText = "";
            let lastRoundToolCalls: StreamToolCall[] = [];

            for (let step = 0; step < maxSteps; step++) {
              const messages: ChatMessage[] = [
                ...buildSystemMessages(
                  agentOn,
                  rootForPrompt,
                  workspaceRulesAppend
                ),
                ...history,
              ];

              let roundText = "";
              let roundToolCalls: StreamToolCall[] = [];
              const abortRound = new AbortController();
              let abortedForRepetition = false;
              try {
                await streamChatCompletion(
                  base,
                  messages,
                  (delta) => {
                    roundText += delta;
                    if (hasRunawayRepetition(roundText)) {
                      abortedForRepetition = true;
                      abortRound.abort();
                      return;
                    }
                    panel.webview.postMessage({ type: "delta", text: delta });
                  },
                  {
                    signal: abortRound.signal,
                    onToolCalls: (calls) => {
                      roundToolCalls = calls;
                    },
                  }
                );
              } catch (e) {
                const aborted =
                  (e instanceof Error && e.name === "AbortError") ||
                  (typeof DOMException !== "undefined" &&
                    e instanceof DOMException &&
                    e.name === "AbortError");
                if (!aborted || !abortedForRepetition) {
                  throw e;
                }
              }
              roundText = truncateIfRepetitive(roundText);
              if (abortedForRepetition) {
                panel.webview.postMessage({
                  type: "delta",
                  text: "\n\n*[Bearis: detenido — el modelo repetía el mismo texto. Prueba otro modelo, baja la temperatura o acorta el prompt.]*\n",
                });
              }

              if (!agentOn) {
                history.push({
                  role: "assistant",
                  content: stripToolMarkersForHistory(roundText),
                });
                lastRoundText = roundText;
                lastRoundToolCalls = roundToolCalls;
                break;
              }

              const reads = parseBearisReadBlocks(roundText);
              const lists = parseBearisListBlocks(roundText);
              const searches = parseBearisSearchBlocks(roundText);
              const greps = parseBearisGrepBlocks(roundText);
              const writesInRound = parseBearisWriteBlocks(roundText);
              const patchesInRound = parseBearisPatchBlocks(roundText);

              if (reads.length || lists.length || searches.length || greps.length) {
                if (writesInRound.length || patchesInRound.length) {
                  panel.webview.postMessage({
                    type: "agent",
                    reads: [],
                    listed: [],
                    written: [],
                    errors: [
                      "This reply mixed tools (GREP/SEARCH/LIST/READ) and BEARIS_PATCH/WRITE; only tools were run. Send PATCH or WRITE in the next reply.",
                    ],
                  });
                }

                const rootTools =
                  (await ensureAgentRootForWrites(context)) ?? undefined;
                if (!rootTools) {
                  panel.webview.postMessage({
                    type: "agent",
                    reads: [],
                    listed: [],
                    written: [],
                    errors: [
                      "No folder for agent tools. Open a workspace or run Bearis: Set Agent Folder…. Tools were skipped.",
                    ],
                  });
                  history.push({
                    role: "assistant",
                    content: stripToolMarkersForHistory(roundText),
                  });
                  lastRoundText = "";
                  break;
                }

                const toolBlocks: string[] = [];
                let readOk: string[] = [];
                let readErr: string[] = [];
                let listOk: string[] = [];
                let listErr: string[] = [];
                let searchErr: string[] = [];
                let grepErr: string[] = [];

                if (lists.length) {
                  const lr = await executeBearisLists(
                    rootTools.uri,
                    lists,
                    listMaxDepth,
                    listMaxEntries
                  );
                  toolBlocks.push(lr.text);
                  listOk = lr.listOk;
                  listErr = lr.listErr;
                }
                if (reads.length) {
                  const rr = await executeBearisReads(
                    rootTools.uri,
                    reads,
                    maxReadChars
                  );
                  toolBlocks.push(rr.text);
                  readOk = rr.readOk;
                  readErr = rr.readErr;
                }
                if (searches.length) {
                  const sr = await executeBearisSearches(
                    codeIndexManager,
                    context,
                    rootTools.uri,
                    searches,
                    codeIndexBuild,
                    searchTopK,
                    cfg.get<boolean>("codeIndexEnabled", true)
                  );
                  if (sr.text) toolBlocks.push(sr.text);
                  searchErr = sr.searchErr;
                }
                if (greps.length) {
                  const gr = await executeBearisGreps(
                    rootTools.uri,
                    greps,
                    grepOpts
                  );
                  if (gr.text) toolBlocks.push(gr.text);
                  grepErr = gr.grepErr;
                }

                const toolText = `[Bearis: tool results]\n\n${toolBlocks.join("\n\n")}`;

                let assistantHist = stripBearisGrepBlocks(roundText);
                assistantHist = stripBearisSearchBlocks(assistantHist);
                assistantHist = stripBearisReadBlocks(assistantHist);
                assistantHist = stripBearisListBlocks(assistantHist);
                if (writesInRound.length || patchesInRound.length) {
                  assistantHist = stripBearisPatchBlocks(assistantHist);
                  assistantHist = stripBearisWriteBlocks(assistantHist);
                }
                history.push({
                  role: "assistant",
                  content: assistantHist.trimEnd(),
                });
                history.push({ role: "user", content: toolText });

                const atStepLimit = step === maxSteps - 1;
                const toolErrors: string[] = [];
                if (readErr.length > 0) {
                  toolErrors.push(`Read failed or skipped: ${readErr.join(", ")}`);
                }
                if (listErr.length > 0) {
                  toolErrors.push(`List failed or skipped: ${listErr.join(", ")}`);
                }
                if (searchErr.length > 0) {
                  toolErrors.push(...searchErr);
                }
                if (grepErr.length > 0) {
                  toolErrors.push(...grepErr.map((e) => `Grep: ${e}`));
                }
                if (atStepLimit) {
                  toolErrors.push(
                    `Auto-loop stopped at bearis.maxAgentSteps (${maxSteps}); send another message to continue.`
                  );
                }
                panel.webview.postMessage({
                  type: "agent",
                  reads: readOk,
                  listed: listOk,
                  written: [],
                  errors: toolErrors,
                  info:
                    searches.length > 0 && searchErr.length === 0
                      ? "Code search (TF‑IDF) updated."
                      : greps.length > 0 && grepErr.length === 0
                        ? "Grep completed."
                        : undefined,
                });
                panel.webview.postMessage({
                  type: "delta",
                  text: "\n\n— Bearis: tools —\n\n",
                });
                if (atStepLimit) {
                  lastRoundText = "";
                  break;
                }
                continue;
              }

              history.push({
                role: "assistant",
                content: stripBearisWriteBlocks(stripBearisPatchBlocks(roundText)).trimEnd(),
              });
              lastRoundText = roundText;
              lastRoundToolCalls = roundToolCalls;
              break;
            }

            let writes = parseBearisWriteBlocks(lastRoundText);
            const patches = parseBearisPatchBlocks(lastRoundText);
            let inferredFromMarkdown = false;
            let malformedLegacyToolCall = false;
            let malformedOpenAiToolCall = false;
            if (writes.length === 0 && agentOn && lastRoundToolCalls.length > 0) {
              const parsedOpenAi = parseOpenAiToolCallWrites(lastRoundToolCalls);
              if (parsedOpenAi.writes.length > 0) {
                writes = parsedOpenAi.writes;
              }
              malformedOpenAiToolCall = parsedOpenAi.malformed;
            }
            if (
              writes.length === 0 &&
              agentOn &&
              cfg.get<boolean>("autoApplyMarkdownFences", true)
            ) {
              const inferred = extractFencedFilesAsWrites(lastRoundText);
              if (inferred.length > 0) {
                writes = inferred;
                inferredFromMarkdown = true;
              }
            }
            if (writes.length === 0 && agentOn) {
              const legacy = parseLegacyCreateFileToolCalls(lastRoundText);
              if (legacy.writes.length > 0) {
                writes = legacy.writes;
              }
              malformedLegacyToolCall = legacy.malformed;
            }
            const dedupedWrites = dedupeWritesByPath(writes);
            writes = dedupedWrites.writes;
            if (dedupedWrites.duplicated.length > 0) {
              panel.webview.postMessage({
                type: "agent",
                written: [],
                patched: [],
                errors: [
                  `Ignored duplicate BEARIS_WRITE blocks for: ${dedupedWrites.duplicated.join(", ")}.`,
                ],
              });
            }

            const writePathSet = new Set(writes.map((w) => w.relativePath.trim()));
            let patchWriteConflict = false;
            for (const p of patches) {
              if (writePathSet.has(p.relativePath.trim())) {
                patchWriteConflict = true;
                break;
              }
            }

            if (writes.length || patches.length) {
              if (patchWriteConflict) {
                panel.webview.postMessage({
                  type: "agent",
                  written: [],
                  patched: [],
                  errors: [
                    "The same file appears in both BEARIS_PATCH and BEARIS_WRITE; use one or the other per file.",
                  ],
                });
              } else if (!agentOn) {
                panel.webview.postMessage({
                  type: "agent",
                  written: [],
                  patched: [],
                  errors: ["Agent file writes are disabled (bearis.agentMode)."],
                });
              } else {
                const rootForWrite =
                  (await ensureAgentRootForWrites(context)) ?? undefined;
                if (!rootForWrite) {
                  panel.webview.postMessage({
                    type: "agent",
                    written: [],
                    patched: [],
                    errors: [
                      "No folder selected for writes. Use “Bearis: Set Agent Folder…” or open a workspace folder.",
                    ],
                  });
                } else {
                  const confirmed = await confirmFileChanges(
                    writes,
                    patches,
                    rootForWrite,
                    { inferredFromMarkdown }
                  );
                  if (!confirmed.ok) {
                    panel.webview.postMessage({
                      type: "agent",
                      written: [],
                      patched: [],
                      errors: [
                        confirmed.reason === "validation"
                          ? "Could not show diff preview (patch or path validation failed). See the error notification."
                          : "Changes were not applied (you cancelled or declined).",
                      ],
                    });
                  } else {
                    const patchResult =
                      patches.length > 0
                        ? await applyBearisPatches(rootForWrite.uri, patches)
                        : { patched: [] as string[], errors: [] as string[] };
                    const writeResult =
                      writes.length > 0
                        ? await applyBearisWrites(rootForWrite.uri, writes)
                        : { written: [] as string[], errors: [] as string[] };
                    const allErrors = [...patchResult.errors, ...writeResult.errors];
                    let info: string | undefined;
                    if (inferredFromMarkdown && writeResult.written.length) {
                      info = "Applied from Markdown code fences.";
                    } else if (patchResult.patched.length && !writeResult.written.length) {
                      info = "Patches applied.";
                    } else if (patchResult.patched.length && writeResult.written.length) {
                      info = "Patches and full writes applied.";
                    }
                    panel.webview.postMessage({
                      type: "agent",
                      written: writeResult.written,
                      patched: patchResult.patched,
                      errors: allErrors,
                      info,
                    });
                  }
                }
              }
            } else if (malformedLegacyToolCall || malformedOpenAiToolCall) {
              panel.webview.postMessage({
                type: "agent",
                written: [],
                patched: [],
                errors: [
                  "The model emitted an incomplete file tool call. No file was written. Ask it to retry creating files directly.",
                ],
              });
            }

            panel.webview.postMessage({ type: "done" });
          } catch (e) {
            const message = e instanceof Error ? e.message : String(e);
            history.pop();
            panel.webview.postMessage({ type: "error", message });
          }
        },
        undefined,
        context.subscriptions
      );
    })
  );
}

export function deactivate() {}
