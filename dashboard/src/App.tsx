import { useEffect, useState, useCallback, useRef } from "react";

interface Layer { id: number; name: string; description: string | null; }
interface Container { id: string; display_name: string; storage_layer_id: number; updated_at: string | null; }
interface Bag {
  bag_id: number; container_id: string; lcsc_part_number: string; mfg_part_number: string;
  initial_quantity: number; current_quantity: number; order_number: string | null;
  package_bill_no: string | null; manufacturer_code: string | null; carton_count: string | null;
  packing_date: string | null; warehouse_code: string | null;
  scanned_at: string | null; description: string | null; manufacturer: string | null;
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

type SortCol = string;

export default function App() {
  const [layers, setLayers] = useState<Layer[]>([]);
  const [containers, setContainers] = useState<Container[]>([]);
  const [bags, setBags] = useState<Bag[]>([]);
  const [expandedLayers, setExpandedLayers] = useState<Set<number>>(new Set());
  const [selectedContainer, setSelectedContainer] = useState<string | null>(null);
  const [wsStatus, setWsStatus] = useState("connecting");
  const [editing, setEditing] = useState<{id: number, col: string} | null>(null);
  const [editVal, setEditVal] = useState("");
  const [sort, setSort] = useState<{col: string, desc: boolean}>({col: "scanned_at", desc: true});
  const [sidebarW, setSidebarW] = useState(260);
  const [filter, setFilter] = useState("");
  const resizing = useRef(false);

  const load = useCallback(() => {
    api<Layer[]>("/api/layers").then(setLayers).catch(console.error);
    api<Container[]>("/api/containers").then(setContainers).catch(console.error);
    api<Bag[]>("/api/components").then(setBags).catch(console.error);
  }, []);

  useEffect(() => { load(); }, [load]);

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

  // Keyboard shortcuts
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if (e.key === "r" || e.key === "R") { load(); e.preventDefault(); }
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [load]);

  // Sidebar resize
  useEffect(() => {
    const mousemove = (e: MouseEvent) => { if (resizing.current) setSidebarW(Math.max(120, Math.min(500, e.clientX))); };
    const mouseup = () => { resizing.current = false; };
    window.addEventListener("mousemove", mousemove);
    window.addEventListener("mouseup", mouseup);
    return () => { window.removeEventListener("mousemove", mousemove); window.removeEventListener("mouseup", mouseup); };
  }, []);

  const filteredBags = bags.filter(b => {
    const t = filter.toLowerCase();
    if (!t) return true;
    return b.lcsc_part_number.toLowerCase().includes(t) || b.container_display_name.toLowerCase().includes(t) || b.layer_name.toLowerCase().includes(t) || (b.order_number && b.order_number.toLowerCase().includes(t)) || (b.package_bill_no && b.package_bill_no.toLowerCase().includes(t));
  });

  const sortedBags = [...filteredBags].sort((a, b) => {
    const av = (a as any)[sort.col] ?? ""; const bv = (b as any)[sort.col] ?? "";
    const cmp = typeof av === "number" ? av - bv : String(av).localeCompare(String(bv));
    return sort.desc ? -cmp : cmp;
  });

  const selContainer = containers.find(c => c.id === selectedContainer);
  const bagsInSel = selectedContainer ? sortedBags.filter(b => b.container_id === selectedContainer) : sortedBags;
  const displayBags = selectedContainer ? bagsInSel : sortedBags;

  async function saveEdit(b: Bag) {
    if (editing?.col === "qty") {
      await api("/api/components/quantity", { method: "POST", body: JSON.stringify({ bag_id: b.bag_id, quantity: parseInt(editVal) }) }).catch(console.error);
    }
    setEditing(null); load();
  }
  async function delBag(id: number) { if (confirm("Delete?")) { await api(`/api/components/${id}`, { method: "DELETE" }).catch(console.error); load(); } }
  async function delContainer(id: string) { if (confirm("Delete?")) { await api(`/api/containers/${encodeURIComponent(id)}`, { method: "DELETE" }).catch(console.error); load(); } }
  async function delLayer(id: number) { if (confirm("Delete?")) { await api(`/api/layers/${id}`, { method: "DELETE" }).catch(console.error); load(); } }
  async function renameContainer(id: string, n: string) {
    const v = prompt("Name:", n); if (v && v !== n) { await api(`/api/containers/${encodeURIComponent(id)}`, { method: "PATCH", body: JSON.stringify({ display_name: v }) }).catch(console.error); load(); }
  }
  async function addLayer() {
    const n = prompt("Layer name:"); if (n) { await api("/api/layers", { method: "POST", body: JSON.stringify({ name: n }) }).catch(console.error); load(); }
  }

  function toggleSort(col: string) {
    if (sort.col === col) setSort(p => ({ ...p, desc: !p.desc }));
    else setSort({ col, desc: false });
  }

  const SCol = ({ col, children }: { col: string; children: any }) => (
    <th style={thS} onClick={() => toggleSort(col)}>
      {children} <span style={{ color: "#666", fontSize: 10 }}>{sort.col === col ? (sort.desc ? "▼" : "▲") : "⇅"}</span>
    </th>
  );

  return (
    <div style={{ height: "100vh", display: "flex", flexDirection: "column", fontFamily: "'Segoe UI', system-ui, sans-serif", fontSize: 13, background: "#0f1117", color: "#e0e0e0" }}>
      {/* Header */}
      <div style={{ display: "flex", alignItems: "center", gap: 16, padding: "4px 16px", borderBottom: "1px solid #2a2d35", background: "#161822", minHeight: 36 }}>
        <strong style={{ color: "#fff", fontSize: 14 }}>STORE</strong>
        <span style={{ color: "#666", fontSize: 11 }}>Inventory</span>
        <input value={filter} onChange={e => setFilter(e.target.value)} placeholder="Filter…" style={{ marginLeft: 16, padding: "2px 8px", background: "#0f1117", border: "1px solid #2a2d35", borderRadius: 3, color: "#e0e0e0", fontSize: 12, width: 200 }} />
        <span style={{ marginLeft: "auto", fontSize: 11, color: wsStatus === "connected" ? "#4ade80" : "#f87171" }}>
          ● {wsStatus} <span style={{ color: "#666", marginLeft: 8 }}>R: refresh</span>
        </span>
      </div>

      <div style={{ display: "flex", flex: 1, overflow: "hidden" }}>
        {/* Sidebar */}
        <div style={{ width: sidebarW, minWidth: 120, borderRight: "1px solid #2a2d35", overflow: "auto", background: "#161822", fontSize: 12, userSelect: "none" }}>
          <div style={{ padding: "4px 8px", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
            <span style={{ color: "#888", fontSize: 11, fontWeight: 600, textTransform: "uppercase", letterSpacing: 1 }}>Layers</span>
            <button onClick={addLayer} style={{ background: "none", border: "1px solid #2a2d35", color: "#4ade80", borderRadius: 2, cursor: "pointer", fontSize: 11, padding: "0 6px" }}>+</button>
          </div>
          {layers.map(l => (
            <div key={l.id}>
              <div onClick={() => setExpandedLayers(p => { const n = new Set(p); n.has(l.id) ? n.delete(l.id) : n.add(l.id); return n; })}
                style={{ padding: "3px 8px", cursor: "pointer", display: "flex", alignItems: "center", gap: 4, color: expandedLayers.has(l.id) ? "#fff" : "#aaa" }}>
                <span style={{ fontSize: 10 }}>{expandedLayers.has(l.id) ? "▼" : "▶"}</span>
                {l.name}
                <span style={{ marginLeft: "auto", color: "#555", fontSize: 10 }}>{containers.filter(c => c.storage_layer_id === l.id).length}</span>
                <button onClick={e => { e.stopPropagation(); delLayer(l.id); }} style={{ cursor:"pointer", border:"none", background:"none", fontSize:11, color:"#666" }}>✕</button>
              </div>
              {expandedLayers.has(l.id) && containers.filter(c => c.storage_layer_id === l.id).map(c => (
                <div key={c.id}
                  onClick={() => setSelectedContainer(selectedContainer === c.id ? null : c.id)}
                  style={{ padding: "2px 8px 2px 24px", cursor: "pointer", fontSize: 12, display: "flex", alignItems: "center",
                    background: selectedContainer === c.id ? "#1e293b" : "transparent", color: selectedContainer === c.id ? "#fff" : "#aaa" }}>
                  <span style={{ flex: 1, overflow: "hidden", textOverflow: "ellipsis" }}>{c.display_name}</span>
                  <button onClick={e => { e.stopPropagation(); renameContainer(c.id, c.display_name); }} style={{ cursor:"pointer", border:"none", background:"none", fontSize:11, color:"#555" }}>✎</button>
                  <button onClick={e => { e.stopPropagation(); delContainer(c.id); }} style={{ cursor:"pointer", border:"none", background:"none", fontSize:11, color:"#555" }}>✕</button>
                </div>
              ))}
            </div>
          ))}
          {selectedContainer && (
            <div onClick={() => setSelectedContainer(null)} style={{ padding: "4px 8px", cursor: "pointer", color: "#4ade80", fontSize: 11, borderTop: "1px solid #2a2d35", marginTop: 8 }}>
              ← Clear filter
            </div>
          )}
        </div>

        {/* Resize handle */}
        <div onMouseDown={() => resizing.current = true} style={{ width: 4, cursor: "col-resize", background: "#1e1e1e", flexShrink: 0 }} />

        {/* Main table */}
        <div style={{ flex: 1, overflow: "auto" }}>
          {selectedContainer && selContainer && (
            <div style={{ padding: "6px 12px", background: "#1e293b", borderBottom: "1px solid #2a2d35", fontSize: 12, color: "#94a3b8" }}>
              Container: <strong style={{ color: "#fff" }}>{selContainer.display_name}</strong>
              <span style={{ color: "#555" }}> ({selContainer.id})</span>
              <span style={{ marginLeft: 16, color: "#4ade80" }}>{bagsInSel.length} bags</span>
            </div>
          )}
          <table style={{ width: "100%", borderCollapse: "collapse", minWidth: 900 }}>
            <thead>
              <tr style={{ background: "#1e1e24", position: "sticky", top: 0, zIndex: 1 }}>
                <SCol col="bag_id">#</SCol>
                <SCol col="lcsc_part_number">Part</SCol>
                <SCol col="current_quantity">Qty</SCol>
                <SCol col="order_number">Order</SCol>
                <SCol col="package_bill_no">PBN</SCol>
                <SCol col="packing_date">PDI</SCol>
                <SCol col="scanned_at">Added</SCol>
                <SCol col="container_display_name">Container</SCol>
                <SCol col="layer_name">Layer</SCol>
                <th style={{...thS, width: 30}}></th>
              </tr>
            </thead>
            <tbody>
              {displayBags.length === 0 && (
                <tr><td colSpan={10} style={{ padding: 32, textAlign: "center", color: "#555" }}>
                  {filter ? "No matches" : selectedContainer ? "No bags in this container" : "No components"}
                </td></tr>
              )}
              {displayBags.map((b, i) => (
                <tr key={b.bag_id} style={{ background: i % 2 === 0 ? "#0f1117" : "#13151d" }}
                  onMouseEnter={e => (e.currentTarget as HTMLElement).style.background = "#1e293b"}
                  onMouseLeave={e => (e.currentTarget as HTMLElement).style.background = i % 2 === 0 ? "#0f1117" : "#13151d"}>
                  <td style={{...tdS, color: "#555", fontFamily: "monospace", fontSize: 10 }}>{b.bag_id}</td>
                  <td style={{...tdS, fontFamily: "monospace", fontSize: 12 }}>
                    <a href={`https://www.lcsc.com/product-detail/${b.lcsc_part_number}.html`} target="_blank" rel="noopener" style={{ color: "#60a5fa", textDecoration: "none" }}>{b.lcsc_part_number}</a>
                  </td>
                  <td style={tdS} onDoubleClick={() => { setEditing({id: b.bag_id, col: "qty"}); setEditVal(String(b.current_quantity)); }}>
                    {editing?.id === b.bag_id && editing?.col === "qty" ? (
                      <input autoFocus value={editVal} onChange={e => setEditVal(e.target.value)} onBlur={() => saveEdit(b)} onKeyDown={e => { if(e.key === "Enter") saveEdit(b); if(e.key === "Escape") setEditing(null); }}
                        style={{ width: 60, background: "#0f1117", border: "1px solid #3b82f6", borderRadius: 2, padding: "1px 4px", fontSize: 13, color: "#fff" }} />
                    ) : <span style={{ fontWeight: 700, cursor: "pointer", color: "#facc15" }}>{b.current_quantity}</span>}
                  </td>
                  <td style={{...tdS, fontFamily: "monospace", fontSize: 11, color: "#94a3b8"}}>{b.order_number || "—"}</td>
                  <td style={{...tdS, fontFamily: "monospace", fontSize: 11, color: "#94a3b8"}}>{b.package_bill_no || "—"}</td>
                  <td style={{...tdS, fontSize: 11, color: "#94a3b8"}}>{b.packing_date ? b.packing_date.slice(0,10) : "—"}</td>
                  <td style={{...tdS, fontSize: 11, color: "#666"}}>{b.scanned_at ? b.scanned_at.replace("T"," ").slice(0,16) : "—"}</td>
                  <td style={tdS}>{b.container_display_name}</td>
                  <td style={tdS}>{b.layer_name}</td>
                  <td style={tdS}><button onClick={() => delBag(b.bag_id)} style={{ cursor:"pointer", border:"none", background:"none", color:"#666", fontSize:12 }}>✕</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          <div style={{ padding: "4px 12px", color: "#555", fontSize: 11, borderTop: "1px solid #2a2d35", background: "#161822" }}>
            {displayBags.length} bag{displayBags.length !== 1 ? "s" : ""} {selectedContainer ? "in selected container" : ""}
          </div>
        </div>
      </div>
    </div>
  );
}

const thS: React.CSSProperties = { padding: "5px 8px", textAlign: "left", fontSize: 10, fontWeight: 600, borderBottom: "1px solid #2a2d35", color: "#888", cursor: "pointer", whiteSpace: "nowrap", userSelect: "none" };
const tdS: React.CSSProperties = { padding: "3px 8px", borderBottom: "1px solid #1a1c24", fontSize: 13, verticalAlign: "middle" };
