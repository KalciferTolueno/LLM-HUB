/**
 * Small / local models sometimes degenerate into repeating the same token or filename.
 * Detect that early and stop the stream so the UI does not fill with spam.
 */

const TAIL_CHARS = 6000;

/**
 * True if the end of `accumulated` looks like a repetition loop (same few tokens many times).
 */
export function hasRunawayRepetition(accumulated: string): boolean {
  const tail = accumulated.slice(-TAIL_CHARS).trim();
  if (tail.length < 80) return false;

  // "foo foo foo ..." (same whitespace-delimited token)
  const oneLine = tail.replace(/\s+/g, " ").trim();
  const m = /^(\S{2,120})(?: \1){24,}$/.exec(oneLine);
  if (m) return true;
  const repeatedFileLike =
    /([a-zA-Z0-9_./-]{2,160}\.[a-zA-Z0-9]{1,10})(?: \1){8,}$/.exec(oneLine);
  if (repeatedFileLike) return true;

  const tokens = tail.split(/\s+/);
  if (tokens.length < 20) return false;

  const recent = tokens.slice(-45);
  const counts = new Map<string, number>();
  for (const t of recent) {
    counts.set(t, (counts.get(t) ?? 0) + 1);
  }
  let max = 0;
  for (const c of counts.values()) {
    max = Math.max(max, c);
  }
  const unique = counts.size;
  if (unique === 1 && max >= 18 && recent[0].length >= 3) return true;
  if (unique <= 3 && max >= 24) return true;
  if (unique <= 4 && max >= 28) return true;

  // Same line repeated many times at end (common with path spam)
  const lines = tail.split("\n");
  const tailLines = lines.slice(-18).map((l) => l.trim()).filter(Boolean);
  if (tailLines.length >= 12) {
    const lineSet = new Set(tailLines);
    if (lineSet.size === 1 && tailLines[0].length >= 3) return true;
  }

  return false;
}

/**
 * Trim from the end until repetition heuristic clears (best-effort for history / tool parse).
 */
export function truncateIfRepetitive(text: string): string {
  if (!hasRunawayRepetition(text)) return text;
  const step = 2000;
  let t = text;
  let guard = 0;
  while (hasRunawayRepetition(t) && t.length > step && guard < 40) {
    t = t.slice(0, -step).trimEnd();
    guard++;
  }
  return t;
}
