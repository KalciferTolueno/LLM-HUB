/** Bearis /v1/chat/completions (streaming). */

export type ChatRole = "system" | "user" | "assistant";

export interface ChatMessage {
  role: ChatRole;
  content: string;
}

export interface StreamToolCall {
  id: string;
  name: string;
  arguments: string;
}

type SseDeltaToolCall = {
  index?: number;
  id?: string;
  type?: string;
  function?: { name?: string; arguments?: string };
};

type SseChunk = {
  choices?: Array<{
    delta?: { content?: string; role?: string; tool_calls?: SseDeltaToolCall[] };
    finish_reason?: string | null;
  }>;
};

export function completionsUrl(apiBaseUrl: string): string {
  const base = apiBaseUrl.replace(/\/$/, "");
  return `${base}/chat/completions`;
}

/** Sent with strict agent mode so models emit OpenAI-style tool_calls (phone API maps legacy output when needed). */
export const BEARIS_DEFAULT_WRITE_TOOLS: object[] = [
  {
    type: "function",
    function: {
      name: "create_new_file",
      description:
        "Create or overwrite a file under the workspace root. Path is relative with forward slashes.",
      parameters: {
        type: "object",
        properties: {
          filepath: { type: "string", description: "Relative path, e.g. src/app.ts" },
          contents: { type: "string", description: "Full file contents" },
        },
        required: ["filepath", "contents"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "write_file",
      description: "Same as create_new_file (alias for clients that use this name).",
      parameters: {
        type: "object",
        properties: {
          filepath: { type: "string" },
          contents: { type: "string" },
        },
        required: ["filepath", "contents"],
      },
    },
  },
];

/**
 * Stream tokens from Bearis. Parses OpenAI-style SSE (data: {...}\\n\\n, [DONE]).
 */
export async function streamChatCompletion(
  apiBaseUrl: string,
  messages: ChatMessage[],
  onDelta: (text: string) => void,
  options?: {
    signal?: AbortSignal;
    onToolCalls?: (calls: StreamToolCall[]) => void;
    tools?: object[];
    tool_choice?: string | object;
  }
): Promise<void> {
  const url = completionsUrl(apiBaseUrl);
  const requestBody: Record<string, unknown> = {
    // Expose a single logical model label to match "Bearis only" UX.
    model: "BEARIS",
    messages,
    stream: true,
  };
  if (options?.tools && options.tools.length > 0) {
    requestBody.tools = options.tools;
    if (options.tool_choice !== undefined) {
      requestBody.tool_choice = options.tool_choice;
    } else {
      requestBody.tool_choice = "auto";
    }
  }
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(requestBody),
    signal: options?.signal,
  });

  if (!res.ok) {
    const t = await res.text().catch(() => "");
    throw new Error(t || `HTTP ${res.status}`);
  }

  const reader = res.body?.getReader();
  if (!reader) {
    const j = (await res.json()) as {
      choices?: Array<{
        message?: {
          content?: string;
          tool_calls?: Array<{
            id?: string;
            function?: { name?: string; arguments?: string };
          }>;
        };
      }>;
    };
    const tc = j?.choices?.[0]?.message?.tool_calls ?? [];
    if (tc.length > 0 && options?.onToolCalls) {
      options.onToolCalls(
        tc
          .map((t, i) => ({
            id: t.id || `call_${i}`,
            name: t.function?.name || "",
            arguments: t.function?.arguments || "",
          }))
          .filter((t) => !!t.name),
      );
    }
    const c = j?.choices?.[0]?.message?.content;
    if (c) onDelta(c);
    return;
  }

  const decoder = new TextDecoder();
  let buffer = "";
  const streamedToolCalls = new Map<number, StreamToolCall>();

  const emitToolCallsIfAny = () => {
    if (!options?.onToolCalls || streamedToolCalls.size === 0) return;
    const ordered = Array.from(streamedToolCalls.entries())
      .sort((a, b) => a[0] - b[0])
      .map(([, v]) => v)
      .filter((v) => !!v.name);
    if (ordered.length > 0) {
      options.onToolCalls(ordered);
    }
  };

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
          const json = JSON.parse(data) as SseChunk;
          const choice = json.choices?.[0];
          const toolCalls = choice?.delta?.tool_calls ?? [];
          if (toolCalls.length > 0) {
            for (const tc of toolCalls) {
              const idx = typeof tc.index === "number" ? tc.index : 0;
              const prev = streamedToolCalls.get(idx) ?? {
                id: tc.id || `call_${idx}`,
                name: "",
                arguments: "",
              };
              if (tc.id) prev.id = tc.id;
              const fn = tc.function;
              if (fn?.name) prev.name += fn.name;
              if (fn?.arguments) prev.arguments += fn.arguments;
              streamedToolCalls.set(idx, prev);
            }
          }
          const content = json.choices?.[0]?.delta?.content;
          if (content) onDelta(content);
          if (choice?.finish_reason === "tool_calls") {
            emitToolCallsIfAny();
          }
        } catch {
          /* ignore malformed line */
        }
      }
    }
  }
  emitToolCallsIfAny();
}
