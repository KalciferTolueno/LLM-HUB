import * as vscode from "vscode";
import type { AgentRootInfo } from "./agentRoot";
import { fileUriUnderRoot, type ParsedPatch, type ParsedWrite } from "./agent";
import {
  computePatchedText,
  mergePatchesByPath,
} from "./patchApply";

const SCHEME = "bearis-proposed";

export type BearisConfirmResult =
  | { ok: true }
  | { ok: false; reason: "user" | "validation" };

type DiffEntry = {
  rel: string;
  leftUri: vscode.Uri;
  rightUri: vscode.Uri;
  leftLabel: string;
  kind: "patch" | "write";
};

type DiffPickItem = vscode.QuickPickItem & { entry: DiffEntry };

/**
 * If there are more entries than `defaultCap`, show a multi-select quick pick (first `defaultCap` pre-selected).
 * @returns Selected entries, or `undefined` if the user dismissed the picker (cancel preview).
 */
function pickDiffFilesToPreview(
  diffItems: DiffEntry[],
  defaultCap: number
): Promise<DiffEntry[] | undefined> {
  if (diffItems.length <= defaultCap) {
    return Promise.resolve(diffItems);
  }

  return new Promise((resolve) => {
    const qp = vscode.window.createQuickPick<DiffPickItem>();
    let accepted = false;
    qp.canSelectMany = true;
    qp.title = "Bearis: choose files to diff-preview";
    qp.placeholder = `Space toggles selection · Enter confirms · Esc cancels preview (${diffItems.length} files, ${defaultCap} pre-selected)`;
    const qItems: DiffPickItem[] = diffItems.map((d) => ({
      label: d.rel,
      description: d.kind === "patch" ? "PATCH" : "WRITE",
      detail: d.leftLabel,
      entry: d,
    }));
    qp.items = qItems;
    qp.selectedItems = qItems.slice(0, defaultCap);

    const btnAll: vscode.QuickInputButton = {
      iconPath: new vscode.ThemeIcon("check-all"),
      tooltip: `Select all ${diffItems.length} files`,
    };
    const btnClear: vscode.QuickInputButton = {
      iconPath: new vscode.ThemeIcon("clear-all"),
      tooltip: "Clear selection",
    };
    const btnReset: vscode.QuickInputButton = {
      iconPath: new vscode.ThemeIcon("list-selection"),
      tooltip: `First ${defaultCap} files (default batch)`,
    };
    qp.buttons = [btnAll, btnClear, btnReset];

    qp.onDidTriggerButton((btn) => {
      if (btn === btnAll) {
        qp.selectedItems = [...qItems];
      } else if (btn === btnClear) {
        qp.selectedItems = [];
      } else if (btn === btnReset) {
        qp.selectedItems = qItems.slice(0, defaultCap);
      }
    });

    qp.onDidAccept(() => {
      accepted = true;
      const picked = qp.selectedItems.map((x) => x.entry);
      qp.dispose();
      resolve(picked);
    });

    qp.onDidHide(() => {
      if (!accepted) {
        resolve(undefined);
      }
    });

    qp.show();
  });
}

function mergeWrites(writes: ParsedWrite[]): ParsedWrite[] {
  const m = new Map<string, ParsedWrite>();
  for (const w of writes) {
    m.set(w.relativePath.trim(), w);
  }
  return [...m.values()];
}

/** Distinct path prefixes so patch vs write virtual docs never share a URI. */
function virtualDocUri(
  rel: string,
  role: "patch-proposed" | "write-proposed" | "empty-new"
): vscode.Uri {
  const safe = rel.split("/").filter(Boolean).map(encodeURIComponent).join("/");
  const prefix =
    role === "patch-proposed" ? "p" : role === "write-proposed" ? "w" : "n";
  return vscode.Uri.parse(`${SCHEME}:/${prefix}/${safe}`);
}

export class BearisDiffPreview implements vscode.TextDocumentContentProvider {
  private readonly docs = new Map<string, string>();

  provideTextDocumentContent(uri: vscode.Uri): string {
    return this.docs.get(uri.toString()) ?? "";
  }

  set(uri: vscode.Uri, body: string): void {
    this.docs.set(uri.toString(), body);
  }

  clear(): void {
    this.docs.clear();
  }

  register(context: vscode.ExtensionContext): void {
    context.subscriptions.push(
      vscode.workspace.registerTextDocumentContentProvider(SCHEME, this)
    );
  }

  /**
   * Validate changes, optionally open vscode.diff for each file, then confirm.
   */
  async confirmChanges(
    root: AgentRootInfo,
    writes: ParsedWrite[],
    patches: ParsedPatch[],
    opts?: { inferredFromMarkdown?: boolean }
  ): Promise<BearisConfirmResult> {
    const cfg = vscode.workspace.getConfiguration("bearis");
    if (!cfg.get<boolean>("confirmWrites", true)) {
      return { ok: true };
    }
    if (writes.length === 0 && patches.length === 0) {
      return { ok: true };
    }

    const lines: string[] = [];
    for (const p of patches) {
      lines.push(
        `• [patch] ${p.relativePath.trim()} (${p.hunks.length} hunk(s))`
      );
    }
    for (const w of writes) {
      lines.push(`• [write] ${w.relativePath.trim()}`);
    }
    const maxLines = 40;
    const list =
      lines.length > maxLines
        ? `${lines.slice(0, maxLines).join("\n")}\n… and ${lines.length - maxLines} more`
        : lines.join("\n");
    const mdNote = opts?.inferredFromMarkdown
      ? "Paths were inferred from Markdown code fences (disable: bearis.autoApplyMarkdownFences).\n\n"
      : "";
    const detail = `${mdNote}Root: ${root.uri.fsPath}\n\n${list}`;

    const title =
      patches.length > 0 && writes.length > 0
        ? `Bearis: apply ${patches.length} patch(es) and ${writes.length} full write(s)?`
        : patches.length > 0
          ? `Bearis: apply ${patches.length} patch file(s)?`
          : `Bearis will create or overwrite ${writes.length} file(s).`;

    const previewOn = cfg.get<boolean>("previewDiffBeforeApply", true);

    if (!previewOn) {
      const choice = await vscode.window.showWarningMessage(
        title,
        { modal: true, detail },
        "Allow",
        "Deny"
      );
      return choice === "Allow" ? { ok: true } : { ok: false, reason: "user" };
    }

    const first = await vscode.window.showWarningMessage(
      title,
      { modal: true, detail },
      "Preview diff",
      "Apply without preview",
      "Cancel"
    );
    if (first === "Cancel" || first === undefined) {
      return { ok: false, reason: "user" };
    }
    if (first === "Apply without preview") {
      return { ok: true };
    }

    const rootUri = root.uri;
    const mergedPatches = mergePatchesByPath(patches);
    const mergedWrites = mergeWrites(writes);
    const validationErrors: string[] = [];
    const diffItems: DiffEntry[] = [];

    for (const p of mergedPatches) {
      const rel = p.relativePath.trim();
      const target = fileUriUnderRoot(rootUri, rel);
      if (!target) {
        validationErrors.push(`${rel}: invalid path`);
        continue;
      }
      let oldText = "";
      try {
        const raw = await vscode.workspace.fs.readFile(target);
        oldText = new TextDecoder("utf-8", { fatal: false })
          .decode(raw)
          .replace(/\r\n/g, "\n");
      } catch {
        validationErrors.push(`${rel}: file not found (PATCH needs existing file)`);
        continue;
      }
      const computed = computePatchedText(oldText, p.hunks);
      if (!computed.ok) {
        validationErrors.push(`${rel}: ${computed.error}`);
        continue;
      }
      const leftUri = target;
      const rightUri = virtualDocUri(rel, "patch-proposed");
      this.set(rightUri, computed.text);
      diffItems.push({
        rel,
        leftUri,
        rightUri,
        leftLabel: "current",
        kind: "patch",
      });
    }

    for (const w of mergedWrites) {
      const rel = w.relativePath.trim();
      const target = fileUriUnderRoot(rootUri, rel);
      if (!target) {
        validationErrors.push(`${rel}: invalid path`);
        continue;
      }
      let oldText = "";
      let exists = false;
      try {
        await vscode.workspace.fs.stat(target);
        const raw = await vscode.workspace.fs.readFile(target);
        oldText = new TextDecoder("utf-8", { fatal: false })
          .decode(raw)
          .replace(/\r\n/g, "\n");
        exists = true;
      } catch {
        exists = false;
      }
      const newText = w.content.replace(/\r\n/g, "\n");
      let leftUri: vscode.Uri;
      if (exists) {
        leftUri = target;
      } else {
        leftUri = virtualDocUri(rel, "empty-new");
        this.set(leftUri, "");
      }
      const rightUri = virtualDocUri(rel, "write-proposed");
      this.set(rightUri, newText);
      diffItems.push({
        rel,
        leftUri,
        rightUri,
        leftLabel: exists ? "current" : "(new file)",
        kind: "write",
      });
    }

    if (validationErrors.length > 0) {
      this.clear();
      await vscode.window.showErrorMessage(
        `Cannot preview: ${validationErrors.slice(0, 5).join("; ")}${validationErrors.length > 5 ? "…" : ""}`
      );
      return { ok: false, reason: "validation" };
    }

    const maxTabs = Math.min(
      40,
      Math.max(1, cfg.get<number>("maxDiffPreviewTabs", 12))
    );
    const openBeside = cfg.get<boolean>("openDiffPreviewBeside", true);
    const picked = await pickDiffFilesToPreview(diffItems, maxTabs);
    if (picked === undefined) {
      this.clear();
      return { ok: false, reason: "user" };
    }
    const toOpen = picked;
    const skipped = diffItems.length - toOpen.length;

    /** With multiple preview tabs, preview:true replaces the previous preview tab, so only the last file would remain visible. */
    const ephemeralTab = toOpen.length === 1;

    await vscode.window.withProgress(
      {
        location: vscode.ProgressLocation.Notification,
        title: "Bearis: opening diff previews…",
        cancellable: false,
      },
      async (progress) => {
        const total = toOpen.length;
        for (let i = 0; i < total; i++) {
          const d = toOpen[i]!;
          progress.report({
            message: `${i + 1}/${total} ${d.rel}`,
            increment: total > 0 ? 100 / total : 0,
          });
          const titleDiff = `Bearis: ${d.rel} (${d.leftLabel} → proposed)`;
          const show: vscode.TextDocumentShowOptions = {
            preview: ephemeralTab,
            ...(openBeside && i === 0
              ? { viewColumn: vscode.ViewColumn.Beside }
              : {}),
            ...(i > 0 && total > 4 ? { preserveFocus: true } : {}),
          };
          await vscode.commands.executeCommand(
            "vscode.diff",
            d.leftUri,
            d.rightUri,
            titleDiff,
            show
          );
        }
      }
    );

    const applyDetail =
      skipped > 0
        ? `Opened ${toOpen.length} diff tab(s); ${skipped} other file(s) were not previewed but are included in this apply. All ${diffItems.length} change(s) were validated before preview.`
        : toOpen.length === 0
          ? `No diff tabs opened. All ${diffItems.length} change(s) were validated; Apply still updates every file in the list.`
          : `Opened ${toOpen.length} diff tab(s).`;

    const apply = await vscode.window.showInformationMessage(
      `Apply these changes to disk?`,
      { modal: true, detail: applyDetail },
      "Apply",
      "Cancel"
    );

    this.clear();
    return apply === "Apply"
      ? { ok: true }
      : { ok: false, reason: "user" };
  }
}
