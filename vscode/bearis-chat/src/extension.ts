import * as vscode from "vscode";
import { streamChatCompletion, type ChatMessage } from "./api";
import {
  applyBearisWrites,
  buildActiveFileAppendix,
  buildAgentSystemPrompt,
  buildAgentSystemPromptProvisional,
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
  <div class="hint">Phone: Bearis + local API. Normal Markdown code fences (html, css, …) can be saved as files when there is no BEARIS_WRITE (<code>bearis.autoApplyMarkdownFences</code>). Or use <code>BEARIS_READ</code> / <code>BEARIS_WRITE</code>. Writes may ask confirmation. Set <code>bearis.apiBaseUrl</code> if needed.</div>
  <div id="log"></div>
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
        vscode.postMessage({ type: "send", text: text });
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

export function activate(context: vscode.ExtensionContext) {
  bearisDiffPreview.register(context);
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
        async (msg: { type: string; text?: string }) => {
          if (msg.type !== "send" || !msg.text) return;

          const userText = msg.text.trim();
          const cfg = vscode.workspace.getConfiguration("bearis");
          const agentOn = cfg.get<boolean>("agentMode", true);
          const rootForPrompt = getAgentRoot(context);
          const includeFile = cfg.get<boolean>("includeActiveFile", true);
          const maxChars = cfg.get<number>("maxActiveFileChars", 12000);
          const cap =
            maxChars <= 0 ? 300_000 : Math.min(Math.max(maxChars, 1), 500_000);

          let userPayload = userText;
          if (rootForPrompt && includeFile) {
            userPayload = userText + buildActiveFileAppendix(rootForPrompt.uri, cap);
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

            let lastRoundText = "";

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
              await streamChatCompletion(base, messages, (delta) => {
                roundText += delta;
                panel.webview.postMessage({ type: "delta", text: delta });
              });

              if (!agentOn) {
                history.push({
                  role: "assistant",
                  content: stripToolMarkersForHistory(roundText),
                });
                lastRoundText = roundText;
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
              break;
            }

            let writes = parseBearisWriteBlocks(lastRoundText);
            const patches = parseBearisPatchBlocks(lastRoundText);
            let inferredFromMarkdown = false;
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
