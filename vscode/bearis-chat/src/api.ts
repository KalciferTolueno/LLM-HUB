/** Bearis /v1/chat/completions (streaming). */

export type ChatRole = "system" | "user" | "assistant";

export interface ChatMessage {
  role: ChatRole;
  content: string;
}

export function completionsUrl(apiBaseUrl: string): string {
  const base = apiBaseUrl.replace(/\/$/, "");
  return `${base}/chat/completions`;
}

/**
 * Stream tokens from Bearis. Parses OpenAI-style SSE (data: {...}\\n\\n, [DONE]).
 */
export async function streamChatCompletion(
  apiBaseUrl: string,
  messages: ChatMessage[],
  onDelta: (text: string) => void,
  options?: { signal?: AbortSignal }
): Promise<void> {
  const url = completionsUrl(apiBaseUrl);
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      model: "local",
      messages,
      stream: true,
    }),
    signal: options?.signal,
  });

  if (!res.ok) {
    const t = await res.text().catch(() => "");
    throw new Error(t || `HTTP ${res.status}`);
  }

  const reader = res.body?.getReader();
  if (!reader) {
    const j = (await res.json()) as {
      choices?: Array<{ message?: { content?: string } }>;
    };
    const c = j?.choices?.[0]?.message?.content;
    if (c) onDelta(c);
    return;
  }

  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    const blocks = buffer.split("\n\n");
    buffer = blocks.pop() ?? "";

    for (const block of blocks) {
      for (const line of block.split("\n")) {
        const trimmed = line.trim();
        if (!trimmed.startsWith("data:")) continue;
        const data = trimmed.slice(5).trim();
        if (data === "[DONE]") continue;
        try {
          const json = JSON.parse(data) as {
            choices?: Array<{ delta?: { content?: string; role?: string } }>;
          };
          const content = json.choices?.[0]?.delta?.content;
          if (content) onDelta(content);
        } catch {
          /* ignore malformed line */
        }
      }
    }
  }
}
