import { useEffect, useState, useCallback, useRef } from "react";

interface Layer { id: number; name: string; description: string | null; }
interface Container { id: string; display_name: string; storage_layer_id: number; updated_at: string | null; }
interface Bag {
  bag_id: number; container_id: string; lcsc_part_number: string; mfg_part_number: string;
  initial_quantity: number; current_quantity: number; order_number: string | null;
  package_bill_no: string | null; manufacturer_code: string | null; carton_count: string | null;
  packing_date: string | null; warehouse_code: string | null;
  scanned_at: string | null; updated_at: string | null; description: string | null; manufacturer: string | null;
  package_type: string | null; datasheet_url: string | null;
  container_display_name: string; layer_name: string; layer_id: number;
}

const BASE = import.meta.env.VITE_API_BASE_URL || "";
const WS = import.meta.env.VITE_WS_URL || "ws://localhost:8000/ws";

async function api<T>(path: string, opts?: RequestInit): Promise<T> {
  const r = await fetch(`${BASE}${path}`, { headers: { "Content-Type": "application/json" }, ...opts });
  if (!r.ok) { const e = await r.json().catch(() => ({ error: r.statusText })); throw new Error(e.error || `HTTP ${r.status}`); }
  return r.json();
}

export default function App() {
  const [layers, setLayers] = useState<Layer[]>([]);
  const [containers, setContainers] = useState<Container[]>([]);
  const [bags, setBags] = useState<Bag[]>([]);
  const [expandedLayers, setExpandedLayers] = useState<Set<number>>(new Set());
  const [selectedContainer, setSelectedContainer] = useState<string | null>(null);
  const [selectedBag, setSelectedBag] = useState<Bag | null>(null);
  const [wsStatus, setWsStatus] = useState("connecting");
  const [editing, setEditing] = useState<{id: number, col: string} | null>(null);
  const [editVal, setEditVal] = useState("");
  const [sort, setSort] = useState<{col: string, desc: boolean}>({col: "scanned_at", desc: true});
  const [sidebarW, setSidebarW] = useState(260);
  const [iframeH, setIframeH] = useState(400);
  const [filter, setFilter] = useState("");
  const resizing = useRef(false);
  const resizingIframe = useRef(false);

  const load = useCallback(() => {
    api<Layer[]>("/api/layers").then(setLayers).catch(console.error);
    api<Container[]>("/api/containers").then(setContainers).catch(console.error);
    api<Bag[]>("/api/components").then(setBags).catch(console.error);
  }, []);

  useEffect(() => { load(); }, [load]);

  // WebSocket
  useEffect(() => {
    let ws: WebSocket; let t: ReturnType<typeof setTimeout>;
    const c = () => {
      setWsStatus("connecting");
      try {
        ws = new WebSocket(WS);
        ws.onopen = () => setWsStatus("connected");
        ws.onmessage = () => load();
        ws.onclose = () => { setWsStatus("disconnected"); t = setTimeout(c, 3000); };
        ws.onerror = () => ws?.close();
      } catch { t = setTimeout(c, 3000); }
    };
    c();
    return () => { clearTimeout(t); ws?.close(); };
  }, [load]);

  // Keyboard shortcut
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === "r" || e.key === "R") { load(); e.preventDefault(); } };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [load]);

  // Sidebar + iframe resize
  useEffect(() => {
    const mm = (e: MouseEvent) => {
      if (resizing.current) setSidebarW(Math.max(120, Math.min(500, e.clientX)));
      if (resizingIframe.current) setIframeH(Math.max(120, Math.min(window.innerHeight * 0.75, window.innerHeight - e.clientY - 35)));
    };
    const mu = () => { resizing.current = false; resizingIframe.current = false; };
    window.addEventListener("mousemove", mm); window.addEventListener("mouseup", mu);
    return () => { window.removeEventListener("mousemove", mm); window.removeEventListener("mouseup", mu); };
  }, []);

  const filteredBags = bags.filter(b => {
    const t = filter.toLowerCase();
    if (!t) return true;
    return b.lcsc_part_number.toLowerCase().includes(t) || b.container_display_name.toLowerCase().includes(t) || b.layer_name.toLowerCase().includes(t) || (b.order_number?.toLowerCase().includes(t)) || (b.package_bill_no?.toLowerCase().includes(t));
  });

  const sortedBags = [...filteredBags].sort((a, b) => {
    const av = (a as any)[sort.col] ?? ""; const bv = (b as any)[sort.col] ?? "";
    const cmp = typeof av === "number" ? av - (bv as number) : String(av).localeCompare(String(bv));
    return sort.desc ? -cmp : cmp;
  });

  const displayBags = selectedContainer ? sortedBags.filter(b => b.container_id === selectedContainer) : sortedBags;
  const selContainer = containers.find(c => c.id === selectedContainer);

  async function saveEdit(b: Bag) {
    if (editing?.col === "qty") { await api("/api/components/quantity", { method: "POST", body: JSON.stringify({ bag_id: b.bag_id, quantity: parseInt(editVal) }) }).catch(console.error); }
    setEditing(null); load();
  }
  async function delBag(id: number) { if (confirm("Delete?")) { await api(`/api/components/${id}`, { method: "DELETE" }).catch(console.error); load(); } }
  async function delContainer(id: string) { if (confirm("Delete?")) { await api(`/api/containers/${encodeURIComponent(id)}`, { method: "DELETE" }).catch(console.error); load(); } }
  async function delLayer(id: number) { if (confirm("Delete?")) { await api(`/api/layers/${id}`, { method: "DELETE" }).catch(console.error); load(); } }
  async function renameContainer(id: string, n: string) { const v = prompt("Name:", n); if (v && v !== n) { await api(`/api/containers/${encodeURIComponent(id)}`, { method: "PATCH", body: JSON.stringify({ display_name: v }) }).catch(console.error); load(); } }
  async function addLayer() { const n = prompt("Layer name:"); if (n) { await api("/api/layers", { method: "POST", body: JSON.stringify({ name: n }) }).catch(console.error); load(); } }

  function toggleSort(col: string) {
    if (sort.col === col) setSort(p => ({ ...p, desc: !p.desc }));
    else setSort({ col, desc: false });
  }

  return (
    <div className="h-screen flex flex-col" style={{ background: "var(--color-surface-900)" }}>
      {/* Header */}
      <header className="flex items-center gap-4 px-4 border-b text-text-primary"
        style={{ minHeight: 36, background: "var(--color-surface-700)", borderColor: "var(--color-surface-300)" }}>
        <strong className="text-[14px]" style={{ color: "#fff" }}>STORE</strong>
        <span className="text-text-dim text-[11px]">Inventory</span>
        <input value={filter} onChange={e => setFilter(e.target.value)} placeholder="Filter…"
          className="ml-4 px-2 py-[2px] rounded text-[12px] border"
          style={{ width: 200, background: "var(--color-surface-900)", borderColor: "var(--color-surface-300)", color: "var(--color-text-primary)" }} />
        <span className="ml-auto text-[11px]" style={{ color: wsStatus === "connected" ? "var(--color-accent-green)" : "var(--color-accent-red)" }}>
          ● {wsStatus}
        </span>
      </header>

      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar */}
        <div className="overflow-auto border-r select-none flex-shrink-0"
          style={{ width: sidebarW, minWidth: 120, background: "var(--color-surface-700)", borderColor: "var(--color-surface-300)", fontSize: 12 }}>
          <div className="flex items-center justify-between px-2 py-1">
            <span className="text-text-dim text-[11px] font-semibold uppercase tracking-wide">Layers</span>
            <button onClick={addLayer} className="border rounded cursor-pointer text-[11px] px-[6px]"
              style={{ borderColor: "var(--color-surface-300)", color: "var(--color-accent-green)", background: "none" }}>+</button>
          </div>
          {layers.map(l => (
            <div key={l.id}>
              <div onClick={() => setExpandedLayers(p => { const n = new Set(p); n.has(l.id) ? n.delete(l.id) : n.add(l.id); return n; })}
                className="flex items-center gap-1 px-2 py-[3px] cursor-pointer"
                style={{ color: expandedLayers.has(l.id) ? "#fff" : "var(--color-text-muted)" }}>
                <span className="text-[10px]">{expandedLayers.has(l.id) ? "▼" : "▶"}</span>
                {l.name}
                <span className="ml-auto text-text-dim text-[10px]">{containers.filter(c => c.storage_layer_id === l.id).length}</span>
                <button onClick={e => { e.stopPropagation(); delLayer(l.id); }} className="cursor-pointer border-none bg-transparent text-text-dim text-[11px]" style={{ color: "var(--color-text-muted)" }}>✕</button>
              </div>
              {expandedLayers.has(l.id) && containers.filter(c => c.storage_layer_id === l.id).map(c => (
                <div key={c.id} onClick={() => { setSelectedContainer(selectedContainer === c.id ? null : c.id); setSelectedBag(null); }}
                  className="flex items-center px-2 py-[2px] text-[12px] cursor-pointer"
                  style={{ paddingLeft: 24, background: selectedContainer === c.id ? "var(--color-surface-400)" : "transparent", color: selectedContainer === c.id ? "#fff" : "var(--color-text-muted)" }}>
                  <span className="flex-1 truncate">{c.display_name}</span>
                  <button onClick={e => { e.stopPropagation(); renameContainer(c.id, c.display_name); }} className="cursor-pointer border-none bg-transparent text-[11px]" style={{ color: "var(--color-text-muted)" }}>✎</button>
                  <button onClick={e => { e.stopPropagation(); delContainer(c.id); }} className="cursor-pointer border-none bg-transparent text-[11px]" style={{ color: "var(--color-text-muted)" }}>✕</button>
                </div>
              ))}
            </div>
          ))}
          {selectedContainer && (
            <div onClick={() => { setSelectedContainer(null); setSelectedBag(null); }}
              className="px-2 py-1 cursor-pointer text-[11px] border-t mt-2"
              style={{ color: "var(--color-accent-green)", borderColor: "var(--color-surface-300)" }}>
              ← Clear filter
            </div>
          )}
        </div>

        {/* Resize handle */}
        <div onMouseDown={() => resizing.current = true} className="w-[4px] flex-shrink-0 cursor-col-resize" style={{ background: "var(--color-surface-600)" }} />

        {/* Main — flex column, table fills remaining space, iframe at bottom */}
        <div className="flex-1 flex flex-col overflow-hidden">
          <div className="flex-1 overflow-auto">
          {selectedContainer && selContainer && (
            <div className="px-3 py-[6px] text-[12px] border-b"
              style={{ background: "var(--color-surface-400)", borderColor: "var(--color-surface-300)", color: "var(--color-text-secondary)" }}>
              Container: <strong className="text-text-primary">{selContainer.display_name}</strong>
              <span className="text-text-dim"> ({selContainer.id})</span>
              <span className="ml-4" style={{ color: "var(--color-accent-green)" }}>{displayBags.length} bags</span>
            </div>
          )}
          <table className="w-full border-collapse" style={{ minWidth: 900 }}>
            <thead>
              <tr className="sticky top-0 z-10" style={{ background: "var(--color-surface-600)" }}>
                {["bag_id","lcsc_part_number","current_quantity","order_number","package_bill_no","packing_date","scanned_at","container_display_name","layer_name"].map(col => (
                  <th key={col} onClick={() => toggleSort(col)}
                    className="text-left cursor-pointer whitespace-nowrap select-none"
                    style={{ padding: "5px 8px", fontSize: 10, fontWeight: 600, borderBottom: "1px solid var(--color-surface-300)", color: "var(--color-text-muted)" }}>
                    {col === "bag_id" && "#"}
                    {col === "lcsc_part_number" && "Part"}
                    {col === "current_quantity" && "Qty"}
                    {col === "order_number" && "Order"}
                    {col === "package_bill_no" && "PBN"}
                    {col === "packing_date" && "PDI"}
                    {col === "scanned_at" && "Added"}
                    {col === "container_display_name" && "Container"}
                    {col === "layer_name" && "Layer"}
                    <span className="text-text-dim text-[10px] ml-1">{sort.col === col ? (sort.desc ? "▼" : "▲") : "⇅"}</span>
                  </th>
                ))}
                <th style={{ width: 30, borderBottom: "1px solid var(--color-surface-300)" }}></th>
              </tr>
            </thead>
            <tbody>
              {displayBags.length === 0 && (
                <tr><td colSpan={10} className="text-center" style={{ padding: 32, color: "var(--color-text-dim)" }}>
                  {filter ? "No matches" : selectedContainer ? "No bags in this container" : "No components"}
                </td></tr>
              )}
              {displayBags.map((b, i) => (
                <tr key={b.bag_id} onClick={() => setSelectedBag(selectedBag?.bag_id === b.bag_id ? null : b)}
                  className="cursor-pointer"
                  style={{ background: selectedBag?.bag_id === b.bag_id ? "var(--color-surface-400)" : i % 2 === 0 ? "var(--color-surface-900)" : "var(--color-surface-800)" }}
                  onMouseEnter={e => { if (selectedBag?.bag_id !== b.bag_id) (e.currentTarget as HTMLElement).style.background = "var(--color-surface-500)"; }}
                  onMouseLeave={e => { if (selectedBag?.bag_id !== b.bag_id) (e.currentTarget as HTMLElement).style.background = i % 2 === 0 ? "var(--color-surface-900)" : "var(--color-surface-800)"; }}>
                  <td className="text-text-dim font-mono text-[10px]" style={{ padding: "3px 8px", borderBottom: "1px solid var(--color-surface-600)" }}>{b.bag_id}</td>
                  <td className="font-mono text-[12px]" style={{ padding: "3px 8px", borderBottom: "1px solid var(--color-surface-600)" }}>
                    <a href={`https://www.lcsc.com/product-detail/${b.lcsc_part_number}.html`} target="_blank" rel="noopener" onClick={e => e.stopPropagation()} style={{ color: "var(--color-accent-blue)", textDecoration: "none" }}>{b.lcsc_part_number}</a>
                  </td>
                  <td style={{ padding: "3px 8px", borderBottom: "1px solid var(--color-surface-600)" }}
                    onDoubleClick={e => { e.stopPropagation(); setEditing({id: b.bag_id, col: "qty"}); setEditVal(String(b.current_quantity)); }}>
                    {editing?.id === b.bag_id && editing?.col === "qty" ? (
                      <input autoFocus value={editVal} onChange={e => setEditVal(e.target.value)}
                        onBlur={() => saveEdit(b)}
                        onKeyDown={e => { if(e.key === "Enter") saveEdit(b); if(e.key === "Escape") setEditing(null); e.stopPropagation(); }}
                        className="rounded px-1 py-[1px] text-[13px] border"
                        style={{ width: 60, background: "var(--color-surface-900)", borderColor: "var(--color-accent-blue)", color: "#fff" }} />
                    ) : <span className="font-bold cursor-pointer" style={{ color: "var(--color-accent-gold)" }}>{b.current_quantity}</span>}
                  </td>
                  <td className="font-mono text-[11px] text-text-secondary" style={{ padding: "3px 8px", borderBottom: "1px solid var(--color-surface-600)" }}>{b.order_number || "—"}</td>
                  <td className="font-mono text-[11px] text-text-secondary" style={{ padding: "3px 8px", borderBottom: "1px solid var(--color-surface-600)" }}>{b.package_bill_no || "—"}</td>
                  <td className="text-[11px] text-text-secondary" style={{ padding: "3px 8px", borderBottom: "1px solid var(--color-surface-600)" }}>{b.packing_date ? b.packing_date.slice(0,10) : "—"}</td>
                  <td className="text-[11px] text-text-dim" style={{ padding: "3px 8px", borderBottom: "1px solid var(--color-surface-600)" }}>{b.scanned_at ? b.scanned_at.replace("T"," ").slice(0,16) : "—"}</td>
                  <td style={{ padding: "3px 8px", borderBottom: "1px solid var(--color-surface-600)" }}>{b.container_display_name}</td>
                  <td style={{ padding: "3px 8px", borderBottom: "1px solid var(--color-surface-600)" }}>{b.layer_name}</td>
                  <td style={{ padding: "3px 8px", borderBottom: "1px solid var(--color-surface-600)" }}>
                    <button onClick={e => { e.stopPropagation(); delBag(b.bag_id); }} className="cursor-pointer border-none bg-transparent text-[12px]" style={{ color: "var(--color-text-muted)" }}>✕</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
          {/* /inner table scroll div */}
          <div className="px-3 py-1 text-[11px] border-t flex-shrink-0" style={{ color: "var(--color-text-dim)", borderColor: "var(--color-surface-300)", background: "var(--color-surface-700)" }}>
            {displayBags.length} bag{displayBags.length !== 1 ? "s" : ""} {selectedContainer ? "in selected container" : ""}
            {selectedBag && <span className="ml-4">• selected: <strong className="text-text-secondary">{selectedBag.lcsc_part_number}</strong></span>}
          </div>

          {/* LCSC product page — resizable panel */}
          {selectedBag && (
            <>
              {/* Resize handle */}
              <div onMouseDown={() => resizingIframe.current = true}
                className="h-[5px] cursor-row-resize flex-shrink-0 border-t border-b" style={{ borderColor: "var(--color-surface-300)", background: "var(--color-surface-600)" }} />
              <div className="flex-shrink-0" style={{ height: iframeH, background: "var(--color-surface-950)", display: "flex", flexDirection: "column" }}>
                <div className="flex items-center gap-2 px-3 py-1 text-[12px] border-b flex-shrink-0" style={{ background: "var(--color-surface-700)", borderColor: "var(--color-surface-300)" }}>
                  <strong style={{ color: "var(--color-accent-blue)" }}>{selectedBag.lcsc_part_number}</strong>
                  <span className="text-text-dim">—</span>
                  <span className="font-bold" style={{ color: "var(--color-accent-gold)" }}>{selectedBag.current_quantity} pcs</span>
                  <span className="text-text-dim">—</span>
                  <span>{selectedBag.container_display_name} / {selectedBag.layer_name}</span>
                  <a href={`https://www.lcsc.com/product-detail/${selectedBag.lcsc_part_number}.html`} target="_blank" rel="noopener"
                    className="ml-auto text-[11px] underline" style={{ color: "var(--color-accent-blue)" }}>
                    Open in LCSC ↗
                  </a>
                  <button onClick={() => setSelectedBag(null)} className="cursor-pointer border-none bg-transparent text-text-dim text-[16px] leading-none ml-1">✕</button>
                </div>
                <iframe
                  src={`/api/lcsc-proxy/product-detail/${selectedBag.lcsc_part_number}.html`}
                  className="flex-1 w-full border-none"
                  style={{ background: "#fff" }}
                  title={selectedBag.lcsc_part_number}
                />
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
