import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
} from "react";
import "./App.css";

type Role = "user" | "assistant" | "system";

interface Msg {
  id: string;
  serverId?: string;
  role: Role;
  content: string;
  /** Seconds from first token to end of generation (set when this reply finishes). */
  responseTimeSec?: number;
}

interface ChatRow {
  id: string;
  title: string;
  modelName: string;
  updatedAt: number;
}

function uid() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}

const WELCOME_ID = "welcome";

const MAX_WEB_IMAGES = 4;
const MAX_IMAGE_FILE_BYTES = 15 * 1024 * 1024;

interface PendingWebImage {
  id: string;
  name: string;
  mime: string;
  dataBase64: string;
  previewUrl: string;
}

const welcomeAssistant: Msg = {
  id: WELCOME_ID,
  role: "assistant",
  content:
    "Conectado al modelo del teléfono. El historial es el mismo que en Bearis (chats con mensajes). Puedes detener la generación o editar tus mensajes.",
};

function metaLabel(role: Role): string {
  if (role === "user") return "Usuario";
  return "Bearis";
}

interface ModelStatus {
  model_name?: string | null;
  supports_vision?: boolean;
  supports_audio?: boolean;
  category?: string | null;
  context_window?: number | null;
}

function formatDurationSec(sec: number): string {
  if (!Number.isFinite(sec) || sec < 0) return "0.0 s";
  if (sec < 100) return `${sec.toFixed(1)} s`;
  return `${Math.round(sec)} s`;
}

export default function App() {
  const [chats, setChats] = useState<ChatRow[]>([]);
  const [chatId, setChatId] = useState<string | null>(null);
  const [messages, setMessages] = useState<Msg[]>([welcomeAssistant]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState("");
  const messagesViewportRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const generationStartMsRef = useRef<number>(0);
  const [streamingAssistantId, setStreamingAssistantId] = useState<string | null>(
    null
  );
  const [liveElapsedSec, setLiveElapsedSec] = useState(0);
  const [pendingDelete, setPendingDelete] = useState<{
    id: string;
    title: string;
  } | null>(null);
  const [modelStatus, setModelStatus] = useState<ModelStatus | null>(null);
  const stickToBottomRef = useRef(true);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [isNarrow, setIsNarrow] = useState(false);
  const [attachOpen, setAttachOpen] = useState(false);
  const attachMenuRef = useRef<HTMLDivElement>(null);
  const fileTxtRef = useRef<HTMLInputElement>(null);
  const fileImgRef = useRef<HTMLInputElement>(null);
  const [pendingImages, setPendingImages] = useState<PendingWebImage[]>([]);

  /** Reasoning/thinking for compatible on-device models (default off). */
  const [reasoningEnabled, setReasoningEnabled] = useState(() => {
    try {
      return sessionStorage.getItem("bearis_web_reasoning") === "1";
    } catch {
      return false;
    }
  });

  const toggleReasoning = useCallback(() => {
    setReasoningEnabled((prev) => {
      const next = !prev;
      try {
        if (next) sessionStorage.setItem("bearis_web_reasoning", "1");
        else sessionStorage.removeItem("bearis_web_reasoning");
      } catch {
        /* ignore */
      }
      return next;
    });
  }, []);

  const refreshChats = useCallback(async () => {
    try {
      const r = await fetch("./v1/chats");
      if (!r.ok) return;
      const j = (await r.json()) as { chats: ChatRow[] };
      setChats(j.chats ?? []);
    } catch {
      /* ignore */
    }
  }, []);

  const loadMessages = useCallback(async (id: string) => {
    const r = await fetch(`./v1/chats/${encodeURIComponent(id)}/messages`);
    if (!r.ok) throw new Error(await r.text());
    const j = (await r.json()) as {
      messages: {
        id: string;
        role: string;
        content: string;
        generation_duration_ms?: number | null;
      }[];
    };
    const rows: Msg[] = (j.messages ?? []).map((m) => ({
      id: m.id,
      serverId: m.id,
      role: (m.role === "user" ? "user" : "assistant") as Role,
      content: m.content,
      responseTimeSec:
        m.generation_duration_ms != null && m.generation_duration_ms >= 0
          ? m.generation_duration_ms / 1000
          : undefined,
    }));
    setMessages(rows.length ? rows : [welcomeAssistant]);
  }, []);

  useEffect(() => {
    void refreshChats();
    try {
      const s = sessionStorage.getItem("bearis_web_chat_id");
      if (s) setChatId(s);
    } catch {
      /* ignore */
    }
  }, [refreshChats]);

  useEffect(() => {
    const fetchModel = async () => {
      try {
        const r = await fetch("./v1/status");
        if (!r.ok) return;
        const j = (await r.json()) as ModelStatus;
        setModelStatus(j);
      } catch {
        /* ignore */
      }
    };
    void fetchModel();
    const id = window.setInterval(() => void fetchModel(), 5000);
    return () => window.clearInterval(id);
  }, []);

  useEffect(() => {
    const mq = window.matchMedia("(max-width: 768px)");
    const apply = () => {
      const n = mq.matches;
      setIsNarrow(n);
      if (!n) setSidebarOpen(false);
    };
    apply();
    mq.addEventListener("change", apply);
    return () => mq.removeEventListener("change", apply);
  }, []);

  useEffect(() => {
    if (!attachOpen) return;
    const close = (e: MouseEvent) => {
      const el = attachMenuRef.current;
      if (el && !el.contains(e.target as Node)) setAttachOpen(false);
    };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, [attachOpen]);

  useEffect(() => {
    try {
      if (chatId) sessionStorage.setItem("bearis_web_chat_id", chatId);
      else sessionStorage.removeItem("bearis_web_chat_id");
    } catch {
      /* ignore */
    }
  }, [chatId]);

  const handleMessagesScroll = useCallback(() => {
    const el = messagesViewportRef.current;
    if (!el) return;
    const threshold = 120;
    const dist = el.scrollHeight - el.scrollTop - el.clientHeight;
    stickToBottomRef.current = dist < threshold;
  }, []);

  useEffect(() => {
    const viewport = messagesViewportRef.current;
    if (!viewport) return;
    if (!stickToBottomRef.current) return;
    viewport.scrollTo({
      top: viewport.scrollHeight,
      behavior: busy ? "auto" : "smooth",
    });
  }, [messages, busy]);

  const resizeInput = useCallback(() => {
    const el = inputRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 200)}px`;
  }, []);

  useEffect(() => {
    resizeInput();
  }, [input, resizeInput]);

  useEffect(() => {
    if (!busy || !streamingAssistantId) return;
    const tick = () => {
      setLiveElapsedSec(
        (Date.now() - generationStartMsRef.current) / 1000
      );
    };
    tick();
    const id = window.setInterval(tick, 100);
    return () => window.clearInterval(id);
  }, [busy, streamingAssistantId]);

  const stopGeneration = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    void fetch("./v1/chat/cancel", { method: "POST" }).catch(() => {});
    setBusy(false);
  }, []);

  const appendToInput = useCallback(
    (chunk: string) => {
      setInput((prev) => (prev ? `${prev}\n${chunk}` : chunk));
      requestAnimationFrame(() => resizeInput());
    },
    [resizeInput]
  );

  const removePendingImage = useCallback((id: string) => {
    setPendingImages((prev) => {
      const p = prev.find((x) => x.id === id);
      if (p?.previewUrl.startsWith("blob:")) URL.revokeObjectURL(p.previewUrl);
      return prev.filter((x) => x.id !== id);
    });
  }, []);

  const send = useCallback(async () => {
    const text = input.trim();
    const imagesSnap = pendingImages;
    if ((!text && imagesSnap.length === 0) || busy) return;

    const displayContent =
      text ||
      (imagesSnap.length
        ? imagesSnap.map((p) => p.name).join(", ") || "Imagen"
        : "");

    stickToBottomRef.current = true;
    setError(null);
    setInput("");
    const userMsg: Msg = { id: uid(), role: "user", content: displayContent };
    const assistantId = uid();
    generationStartMsRef.current = Date.now();
    setStreamingAssistantId(assistantId);
    setLiveElapsedSec(0);
    setMessages((prev) => [
      ...prev.filter((m) => m.id !== WELCOME_ID),
      userMsg,
      { id: assistantId, role: "assistant", content: "" },
    ]);
    setBusy(true);

    const hist = [...messages.filter((m) => m.id !== WELCOME_ID), userMsg].map(
      (m) => ({
        role: m.role,
        content: m.role === "user" && m.id === userMsg.id ? text : m.content,
      })
    );

    const ac = new AbortController();
    abortRef.current = ac;

    const releasePendingSnap = () => {
      imagesSnap.forEach((p) => {
        if (p.previewUrl.startsWith("blob:")) URL.revokeObjectURL(p.previewUrl);
      });
      setPendingImages([]);
    };

    try {
      const res = await fetch("./v1/chat/completions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        signal: ac.signal,
        body: JSON.stringify({
          model: "local",
          messages: hist,
          stream: true,
          bearis_chat_id: chatId,
          bearis_enable_thinking: reasoningEnabled,
          bearis_images: imagesSnap.map((p) => ({
            mime: p.mime,
            data_base64: p.dataBase64,
          })),
        }),
      });

      const hdrId = res.headers.get("X-Bearis-Chat-Id");
      if (hdrId) setChatId(hdrId);

      if (res.status === 503) {
        setError("No hay modelo cargado en Bearis.");
        setMessages((m) => m.filter((x) => x.id !== assistantId));
        return;
      }

      if (!res.ok) {
        const t = await res.text();
        throw new Error(t || `HTTP ${res.status}`);
      }

      const reader = res.body?.getReader();
      if (!reader) {
        const j = await res.json();
        const c =
          j?.choices?.[0]?.message?.content ??
          j?.choices?.[0]?.text ??
          JSON.stringify(j);
        const elapsedSec =
          (Date.now() - generationStartMsRef.current) / 1000;
        setMessages((m) =>
          m.map((x) =>
            x.id === assistantId
              ? { ...x, content: String(c), responseTimeSec: elapsedSec }
              : x
          )
        );
        releasePendingSnap();
        return;
      }

      const dec = new TextDecoder();
      let buf = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        const parts = buf.split("\n\n");
        buf = parts.pop() ?? "";

        for (const block of parts) {
          const line = block.trim();
          if (!line.startsWith("data:")) continue;
          const payload = line.slice(5).trim();
          if (payload === "[DONE]") continue;
          try {
            const json = JSON.parse(payload);
            const delta = json?.choices?.[0]?.delta?.content;
            if (typeof delta === "string" && delta) {
              setMessages((m) =>
                m.map((x) =>
                  x.id === assistantId
                    ? { ...x, content: x.content + delta }
                    : x
                )
              );
            }
          } catch {
            /* partial */
          }
        }
      }

      const cid = hdrId ?? res.headers.get("X-Bearis-Chat-Id");
      if (cid) {
        setChatId(cid);
        await loadMessages(cid);
        await refreshChats();
      } else {
        const elapsedSec =
          (Date.now() - generationStartMsRef.current) / 1000;
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId && m.role === "assistant"
              ? { ...m, responseTimeSec: elapsedSec }
              : m
          )
        );
      }
      releasePendingSnap();
    } catch (e) {
      if (e instanceof Error && e.name === "AbortError") {
        const elapsedSec =
          (Date.now() - generationStartMsRef.current) / 1000;
        setMessages((m) =>
          m.map((x) =>
            x.id === assistantId
              ? {
                  ...x,
                  content: x.content || "(detenido)",
                  responseTimeSec: elapsedSec,
                }
              : x
          )
        );
      } else {
        const msg = e instanceof Error ? e.message : String(e);
        setError(msg);
        setMessages((m) => m.filter((x) => x.id !== assistantId));
      }
    } finally {
      abortRef.current = null;
      setStreamingAssistantId(null);
      setBusy(false);
    }
  }, [
    input,
    busy,
    messages,
    chatId,
    loadMessages,
    refreshChats,
    reasoningEnabled,
    pendingImages,
  ]);

  const onKey = (e: KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void send();
    }
  };

  const selectChat = async (id: string) => {
    setSidebarOpen(false);
    setChatId(id);
    setError(null);
    try {
      await loadMessages(id);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const newChatSession = () => {
    setSidebarOpen(false);
    setChatId(null);
    setMessages([welcomeAssistant]);
    setError(null);
  };

  const pasteFromClipboard = async () => {
    try {
      const t = await navigator.clipboard.readText();
      if (t) appendToInput(t);
      setAttachOpen(false);
    } catch {
      setError("No se pudo leer el portapapeles.");
      setAttachOpen(false);
    }
  };

  const onTxtFileChange = (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    e.target.value = "";
    if (!f) return;
    const reader = new FileReader();
    reader.onload = () => {
      const text = typeof reader.result === "string" ? reader.result : "";
      if (text) appendToInput(text.slice(0, 120_000));
    };
    reader.readAsText(f);
    setAttachOpen(false);
  };

  const onImgFileChange = (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    e.target.value = "";
    if (!f) return;
    if (f.size > MAX_IMAGE_FILE_BYTES) {
      setError("Imagen demasiado grande (máx. ~15 MB).");
      setAttachOpen(false);
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = typeof reader.result === "string" ? reader.result : "";
      const comma = dataUrl.indexOf(",");
      if (comma < 0) {
        setError("No se pudo leer la imagen.");
        return;
      }
      const header = dataUrl.slice(0, comma);
      const b64 = dataUrl.slice(comma + 1);
      const mimeMatch = /^data:([^;]+);base64$/i.exec(header);
      const mime =
        mimeMatch?.[1]?.trim() ||
        (f.type && f.type.length > 0 ? f.type : "image/jpeg");
      const previewUrl = URL.createObjectURL(f);
      setPendingImages((prev) => {
        if (prev.length >= MAX_WEB_IMAGES) {
          URL.revokeObjectURL(previewUrl);
          return prev;
        }
        return [
          ...prev,
          {
            id: uid(),
            name: f.name || "imagen",
            mime,
            dataBase64: b64,
            previewUrl,
          },
        ];
      });
    };
    reader.onerror = () => setError("No se pudo leer la imagen.");
    reader.readAsDataURL(f);
    setAttachOpen(false);
  };

  const confirmDeleteChat = async () => {
    if (!pendingDelete) return;
    const { id } = pendingDelete;
    setPendingDelete(null);
    setError(null);
    try {
      const r = await fetch(`./v1/chats/${encodeURIComponent(id)}`, {
        method: "DELETE",
      });
      if (!r.ok) throw new Error(await r.text());
      if (chatId === id) {
        newChatSession();
      }
      await refreshChats();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const startEdit = (m: Msg) => {
    if (m.role !== "user" || !m.serverId || !chatId) return;
    setEditingId(m.id);
    setEditDraft(m.content);
  };

  const saveEdit = async () => {
    if (!editingId || !chatId) return;
    const m = messages.find((x) => x.id === editingId);
    if (!m?.serverId) return;
    const body = editDraft.trim();
    if (!body) {
      setEditingId(null);
      return;
    }
    try {
      const r = await fetch(
        `./v1/chats/${encodeURIComponent(chatId)}/messages/${encodeURIComponent(m.serverId)}`,
        {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ content: body }),
        }
      );
      if (!r.ok) throw new Error(await r.text());
      setEditingId(null);
      await loadMessages(chatId);
      await refreshChats();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <div className="silica-shell">
      <svg className="grain-overlay" aria-hidden="true">
        <filter id="silica-grain">
          <feTurbulence
            type="fractalNoise"
            baseFrequency="0.6"
            numOctaves="3"
            stitchTiles="stitch"
          />
          <feColorMatrix type="saturate" values="0" />
        </filter>
        <rect width="100%" height="100%" filter="url(#silica-grain)" />
      </svg>

      <div className="app-container">
        {isNarrow && sidebarOpen && (
          <button
            type="button"
            className="silica-sidebar-backdrop"
            aria-label="Cerrar menú de chats"
            onClick={() => setSidebarOpen(false)}
          />
        )}
        <aside
          className={
            "silica-aside" + (sidebarOpen ? " silica-aside--open" : "")
          }
        >
          <div className="silica-brand-row">
            <div className="brand">Bearis</div>
            <span
              className="silica-model-badge"
              title="Modelo cargado en Bearis"
            >
              {modelStatus?.model_name?.trim() || "Sin modelo cargado"}
            </span>
          </div>

          <div className="nav-group">
            <span className="meta">Sesión</span>
            <button
              type="button"
              className="nav-item nav-item--cta"
              onClick={newChatSession}
            >
              Nuevo chat
            </button>
          </div>

          <div className="nav-group nav-group--grow">
            <span className="meta">Chats activos</span>
            <div className="nav-chat-scroll">
              {chats.map((c) => (
                <div key={c.id} className="nav-chat-row">
                  <button
                    type="button"
                    className={
                      "nav-item nav-item--chat" +
                      (chatId === c.id ? " active" : "")
                    }
                    onClick={() => void selectChat(c.id)}
                  >
                    <span className="nav-chat-title">{c.title || "Chat"}</span>
                    <span className="nav-chat-meta">{c.modelName}</span>
                  </button>
                  <button
                    type="button"
                    className="nav-chat-delete"
                    title="Eliminar chat"
                    aria-label="Eliminar chat"
                    onClick={(e) => {
                      e.stopPropagation();
                      setPendingDelete({
                        id: c.id,
                        title: c.title || "Chat",
                      });
                    }}
                  >
                    <svg
                      width="16"
                      height="16"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2"
                      aria-hidden
                    >
                      <path d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2m3 0v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6h14zM10 11v6M14 11v6" />
                    </svg>
                  </button>
                </div>
              ))}
            </div>
          </div>

          <div className="nav-footer">
            <button
              type="button"
              className="nav-item"
              onClick={() => void refreshChats()}
            >
              Actualizar lista
            </button>
          </div>
        </aside>

        <main className="silica-main">
          <div className="orb orb-1" aria-hidden="true" />
          <div className="orb orb-2" aria-hidden="true" />

          <header className="silica-main-head">
            <button
              type="button"
              className="silica-mobile-toggle"
              aria-label="Abrir lista de chats"
              onClick={() => setSidebarOpen((o) => !o)}
            >
              <svg
                width="22"
                height="22"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                aria-hidden
              >
                <path d="M4 6h16M4 12h16M4 18h16" />
              </svg>
            </button>
            {chatId && (
              <span className="silica-id" title="ID en Bearis">
                {chatId.slice(0, 8)}…
              </span>
            )}
          </header>

          {error && (
            <div className="silica-banner err" role="alert">
              {error}
            </div>
          )}

          <div
            className="messages-viewport"
            ref={messagesViewportRef}
            onScroll={handleMessagesScroll}
          >
            <div className="messages-stack">
            {messages.map((m) => (
              <div
                key={m.id}
                className={
                  "message " + (m.role === "user" ? "user" : "ai")
                }
              >
                {m.role === "user" ? (
                  <div className="silica-user-top">
                    <span className="meta">{metaLabel(m.role)}</span>
                    {m.serverId && chatId && (
                      <button
                        type="button"
                        className="silica-link"
                        onClick={() => startEdit(m)}
                      >
                        Editar
                      </button>
                    )}
                  </div>
                ) : (
                  <div className="silica-assistant-meta">
                    <span className="meta">{metaLabel(m.role)}</span>
                    {busy && streamingAssistantId === m.id ? (
                      <span className="silica-gen-timer" aria-live="polite">
                        {formatDurationSec(liveElapsedSec)}
                      </span>
                    ) : m.responseTimeSec != null ? (
                      <span className="silica-gen-timer silica-gen-timer--final">
                        {formatDurationSec(m.responseTimeSec)}
                      </span>
                    ) : null}
                  </div>
                )}

                {editingId === m.id ? (
                  <div className="message-content silica-edit-box">
                    <textarea
                      rows={4}
                      value={editDraft}
                      onChange={(e) => setEditDraft(e.target.value)}
                    />
                    <div className="silica-edit-actions">
                      <button
                        type="button"
                        className="btn-strata btn-strata--ghost"
                        onClick={() => setEditingId(null)}
                      >
                        Cancelar
                      </button>
                      <button
                        type="button"
                        className="btn-strata btn-strata--primary"
                        onClick={() => void saveEdit()}
                      >
                        Guardar
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="message-content">
                    {m.content || (busy && m.role === "assistant" ? "…" : "")}
                  </div>
                )}
              </div>
            ))}
            </div>
          </div>

          <div className="input-container">
            {pendingImages.length > 0 && (
              <div className="silica-pending-images" aria-label="Imágenes pendientes">
                {pendingImages.map((p) => (
                  <div key={p.id} className="silica-pending-image-chip">
                    <img src={p.previewUrl} alt="" className="silica-pending-thumb" />
                    <span className="silica-pending-name" title={p.name}>
                      {p.name}
                    </span>
                    <button
                      type="button"
                      className="silica-pending-remove"
                      aria-label={`Quitar ${p.name}`}
                      disabled={busy}
                      onClick={() => removePendingImage(p.id)}
                    >
                      ×
                    </button>
                  </div>
                ))}
              </div>
            )}
            <input
              ref={fileTxtRef}
              type="file"
              accept=".txt,text/plain"
              className="silica-file-input"
              aria-hidden
              tabIndex={-1}
              onChange={onTxtFileChange}
            />
            <input
              ref={fileImgRef}
              type="file"
              accept="image/*"
              className="silica-file-input"
              aria-hidden
              tabIndex={-1}
              onChange={onImgFileChange}
            />
            <div className="input-wrapper">
              <div className="input-attach-wrap" ref={attachMenuRef}>
                <button
                  type="button"
                  className="input-attach-btn"
                  disabled={busy}
                  aria-expanded={attachOpen}
                  aria-haspopup="true"
                  title="Añadir contenido"
                  onClick={() => setAttachOpen((o) => !o)}
                >
                  <svg
                    width="20"
                    height="20"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2.2"
                    strokeLinecap="round"
                    aria-hidden
                  >
                    <path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48" />
                  </svg>
                </button>
                {attachOpen && (
                  <div className="silica-attach-menu" role="menu">
                    {modelStatus?.context_window != null && (
                      <div className="silica-attach-hint" role="presentation">
                        Contexto ~{modelStatus.context_window.toLocaleString()}{" "}
                        tokens
                      </div>
                    )}
                    <button
                      type="button"
                      className="silica-attach-item"
                      role="menuitem"
                      onClick={() => void pasteFromClipboard()}
                    >
                      Pegar texto del portapapeles
                    </button>
                    <button
                      type="button"
                      className="silica-attach-item"
                      role="menuitem"
                      onClick={() => {
                        fileTxtRef.current?.click();
                        setAttachOpen(false);
                      }}
                    >
                      Cargar archivo de texto (.txt)
                    </button>
                    {modelStatus?.supports_vision && (
                      <button
                        type="button"
                        className="silica-attach-item"
                        role="menuitem"
                        onClick={() => {
                          fileImgRef.current?.click();
                          setAttachOpen(false);
                        }}
                      >
                        Adjuntar imagen (se envía al modelo por LAN)
                      </button>
                    )}
                    {modelStatus?.supports_audio && (
                      <div className="silica-attach-hint" role="note">
                        Entrada de audio: usa Bearis en el dispositivo para
                        enviar audio al modelo.
                      </div>
                    )}
                    {modelStatus?.category && (
                      <div className="silica-attach-hint" role="note">
                        Categoría: {modelStatus.category}
                      </div>
                    )}
                  </div>
                )}
              </div>
              <textarea
                ref={inputRef}
                rows={1}
                placeholder="Transmitir mensaje…"
                value={input}
                disabled={busy}
                onChange={(e) => {
                  setInput(e.target.value);
                  resizeInput();
                }}
                onKeyDown={onKey}
              />
              {busy ? (
                <button
                  type="button"
                  className="send-btn send-btn--stop"
                  onClick={stopGeneration}
                >
                  Detener
                </button>
              ) : (
                <>
                  <button
                    type="button"
                    className={
                      "reasoning-toggle" +
                      (reasoningEnabled ? " reasoning-toggle--on" : "")
                    }
                    title={
                      reasoningEnabled
                        ? "Razonamiento: activado (más lento en modelos compatibles)"
                        : "Razonamiento: desactivado"
                    }
                    aria-pressed={reasoningEnabled}
                    aria-label={
                      reasoningEnabled
                        ? "Desactivar razonamiento"
                        : "Activar razonamiento"
                    }
                    onClick={toggleReasoning}
                  >
                    <span className="reasoning-toggle__icon" aria-hidden>
                      ◇
                    </span>
                  </button>
                  <button
                    type="button"
                    className="send-btn"
                    disabled={!input.trim() && pendingImages.length === 0}
                    onClick={() => void send()}
                    aria-label="Enviar"
                  >
                    <svg
                      width="18"
                      height="18"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="#05070a"
                      strokeWidth="2.5"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    >
                      <line x1="22" y1="2" x2="11" y2="13" />
                      <polygon points="22 2 15 22 11 13 2 9 22 2" />
                    </svg>
                  </button>
                </>
              )}
            </div>
          </div>
        </main>
      </div>

      {pendingDelete && (
        <div
          className="silica-dialog-backdrop"
          role="presentation"
          onClick={() => setPendingDelete(null)}
        >
            <div
              className="silica-dialog"
              role="dialog"
              aria-modal="true"
              aria-labelledby="silica-delete-title"
              onClick={(e) => e.stopPropagation()}
            >
              <p id="silica-delete-title" className="silica-dialog-title">
                ¿Eliminar este chat?
              </p>
              <p className="silica-dialog-text">
                Se borrará «{pendingDelete.title}» en el teléfono (Bearis) y
                aquí.
              </p>
              <div className="silica-dialog-actions">
                <button
                  type="button"
                  className="btn-strata btn-strata--ghost"
                  onClick={() => setPendingDelete(null)}
                >
                  Cancelar
                </button>
                <button
                  type="button"
                  className="btn-strata btn-strata--danger"
                  onClick={() => void confirmDeleteChat()}
                >
                  Eliminar
                </button>
              </div>
            </div>
          </div>
      )}
    </div>
  );
}
