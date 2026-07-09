import { useEffect, useState, useCallback } from "react";

interface Layer { id: number; name: string; description: string | null; }
interface Container { id: string; display_name: string; storage_layer_id: number; updated_at: string | null; }
interface Bag {
  bag_id: number; container_id: string; lcsc_part_number: string; mfg_part_number: string;
  initial_quantity: number; current_quantity: number;
  package_bill_no: string | null;
  description: string | null; manufacturer: string | null; package_type: string | null; datasheet_url: string | null;
  container_display_name: string; layer_name: string; layer_id: number;
}

const BASE = import.meta.env.VITE_API_BASE_URL || "";
const WS = import.meta.env.VITE_WS_URL || "ws://localhost:8000/ws";

async function api<T>(path: string, opts?: RequestInit): Promise<T> {
  const r = await fetch(`${BASE}${path}`, { headers: { "Content-Type": "application/json" }, ...opts });
  if (!r.ok) { const e = await r.json().catch(() => ({ error: r.statusText })); throw new Error(e.error || `HTTP ${r.status}`); }
  return r.json();
}

type View = "layers" | "containers" | "bags";

export default function App() {
  const [layers, setLayers] = useState<Layer[]>([]);
  const [containers, setContainers] = useState<Container[]>([]);
  const [bags, setBags] = useState<Bag[]>([]);
  const [view, setView] = useState<View>("layers");
  const [selectedLayer, setSelectedLayer] = useState<number | null>(null);
  const [selectedContainer, setSelectedContainer] = useState<string | null>(null);
  const [wsStatus, setWsStatus] = useState("connecting");
  const [editCell, setEditCell] = useState<{row: number; col: string} | null>(null);
  const [editVal, setEditVal] = useState("");
  const [layersExpanded, setLayersExpanded] = useState(true);
  const [containersExpanded, setContainersExpanded] = useState(true);

  const load = useCallback(() => {
    api<Layer[]>("/api/layers").then(setLayers).catch(console.error);
    api<Container[]>("/api/containers").then(setContainers).catch(console.error);
    api<Bag[]>("/api/components").then(setBags).catch(console.error);
  }, []);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    let ws: WebSocket; let timer: ReturnType<typeof setTimeout>;
    function connect() {
      setWsStatus("connecting");
      try {
        ws = new WebSocket(WS);
        ws.onopen = () => setWsStatus("connected");
        ws.onmessage = () => load();
        ws.onclose = () => { setWsStatus("disconnected"); timer = setTimeout(connect, 3000); };
        ws.onerror = () => ws?.close();
      } catch { timer = setTimeout(connect, 3000); }
    }
    connect();
    return () => { clearTimeout(timer); ws?.close(); };
  }, [load]);

  const filteredBags = selectedContainer ? bags.filter(b => b.container_id === selectedContainer)
    : selectedLayer ? bags.filter(b => b.layer_id === selectedLayer)
    : view === "bags" ? bags
    : bags;

  const filteredContainers = selectedLayer ? containers.filter(c => c.storage_layer_id === selectedLayer) : containers;

  async function saveEdit(b: Bag) {
    if (editCell?.col === "qty") {
      await api("/api/components/quantity", { method: "POST", body: JSON.stringify({ container_id: b.container_id, lcsc_part_number: b.lcsc_part_number, quantity: parseInt(editVal) }) }).catch(console.error);
    }
    setEditCell(null); load();
  }

  async function deleteContainer(id: string) {
    if (confirm("Delete this container?")) { await api(`/api/containers/${encodeURIComponent(id)}`, { method: "DELETE" }).catch(console.error); load(); }
  }
  async function deleteBag(id: number) {
    if (confirm("Delete this bag?")) { await api(`/api/components/${id}`, { method: "DELETE" }).catch(console.error); load(); }
  }
  async function deleteLayer(id: number) {
    if (confirm("Delete this layer and everything in it?")) { await api(`/api/layers/${id}`, { method: "DELETE" }).catch(console.error); load(); }
  }

  return (
    <div style={{ height: "100vh", display: "flex", flexDirection: "column", fontFamily: "system-ui, sans-serif", fontSize: 14 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "6px 16px", borderBottom: "1px solid #ccc", background: "#f5f5f5" }}>
        <strong>StorageSystem</strong>
        <span style={{ marginLeft: "auto", fontSize: 12, color: wsStatus === "connected" ? "#16a34a" : "#dc2626" }}>● {wsStatus}</span>
      </div>
      <div style={{ display: "flex", flex: 1, overflow: "hidden" }}>
        <div style={{ width: 260, borderRight: "1px solid #ccc", overflow: "auto", background: "#fafafa", padding: 4, userSelect: "none" }}>
          <div style={{ display: "flex", gap: 2, margin: "6px 4px" }}>
            {(["layers","containers","bags"] as View[]).map(v => (
              <button key={v} onClick={() => setView(v)}
                style={{ flex: 1, padding: "4px 6px", border: "1px solid #ccc", borderRadius: 4, cursor: "pointer",
                  background: view === v ? "#e0e0e0" : "#fff", fontWeight: view === v ? 600 : 400, fontSize: 11 }}>{v}</button>
            ))}
          </div>

          <div style={{ marginTop: 4 }}>
            <div onClick={() => setLayersExpanded(p => !p)} style={{ padding: "4px 8px", cursor: "pointer", fontWeight: 600, fontSize: 13, display: "flex", alignItems: "center", gap: 4 }}>
              <span>{layersExpanded ? "▼" : "▶"}</span> Layers ({layers.length})
              <button onClick={e => { e.stopPropagation(); const n = prompt("Layer name:"); if(n) api("/api/layers", { method: "POST", body: JSON.stringify({name:n}) }).then(load); }}
                style={{ marginLeft: "auto", cursor: "pointer", border: "1px solid #aaa", borderRadius: 3, padding: "1px 6px", fontSize: 11, background:"#fff" }}>+</button>
            </div>
            {layersExpanded && layers.map(l => (
              <div key={l.id}>
                <div onClick={() => setSelectedLayer(selectedLayer === l.id ? null : l.id)}
                  style={{ padding: "3px 8px 3px 24px", cursor: "pointer", display: "flex", alignItems: "center", gap: 4, fontSize: 13,
                    background: selectedLayer === l.id ? "#dbeafe" : "transparent" }}>
                  <span>{selectedLayer === l.id ? "▼" : "▶"}</span> {l.name}
                  <button onClick={e => { e.stopPropagation(); deleteLayer(l.id); }} style={{ marginLeft: "auto", cursor:"pointer", border:"none", background:"none", fontSize:11, color:"#c00" }}>✕</button>
                </div>
                {selectedLayer === l.id && filteredContainers.map(c => (
                  <div key={c.id} onClick={() => { setSelectedContainer(selectedContainer === c.id ? null : c.id); }}
                    style={{ padding: "2px 8px 2px 40px", cursor: "pointer", fontSize: 12,
                      background: selectedContainer === c.id ? "#dbeafe" : "transparent" }}>
                    {c.display_name}
                    <button onClick={e => { e.stopPropagation(); deleteContainer(c.id); }} style={{ marginLeft: 8, cursor:"pointer", border:"none", background:"none", fontSize:10, color:"#c00" }}>✕</button>
                  </div>
                ))}
              </div>
            ))}
          </div>

          {view === "containers" && <div style={{ marginTop: 12, borderTop: "1px solid #ddd", paddingTop: 8 }}>
            <div onClick={() => setContainersExpanded(p => !p)} style={{ padding: "4px 8px", cursor: "pointer", fontWeight: 600, fontSize: 13 }}>
              <span>{containersExpanded ? "▼" : "▶"}</span> All Containers ({containers.length})
            </div>
            {containersExpanded && containers.map(c => (
              <div key={c.id} onClick={() => setSelectedContainer(selectedContainer === c.id ? null : c.id)}
                style={{ padding: "2px 8px 2px 24px", cursor: "pointer", fontSize: 12,
                  background: selectedContainer === c.id ? "#dbeafe" : "transparent" }}>
                {c.display_name}
              </div>
            ))}
          </div>}
        </div>

        <div style={{ flex: 1, overflow: "auto" }}>
          {(view === "layers" || view === "bags") && (
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <thead><tr style={{ background: "#f0f0f0", position: "sticky", top: 0 }}>
                <th style={th}>Bag ID</th><th style={th}>LCSC Part</th><th style={th}>MFG Part</th><th style={th}>Qty</th>
                <th style={th}>Container</th><th style={th}>Layer</th><th style={th}></th>
              </tr></thead>
              <tbody>
                {filteredBags.length === 0 && <tr><td colSpan={7} style={{ padding: 24, textAlign: "center", color: "#999" }}>No components</td></tr>}
                {filteredBags.map((b, i) => (
                  <tr key={b.bag_id} style={{ background: i % 2 === 0 ? "#fff" : "#f9f9f9" }}>
                    <td style={{...td, fontFamily: "monospace", fontSize: 11}} title={b.package_bill_no || ""}>{b.package_bill_no || "—"}</td>
                    <td style={{...td, fontFamily: "monospace", fontSize: 12}}>{b.lcsc_part_number}</td>
                    <td style={td}>{b.mfg_part_number || "—"}</td>
                    <td style={td} onDoubleClick={() => { setEditCell({row: b.bag_id, col: "qty"}); setEditVal(String(b.current_quantity)); }}>
                      {editCell?.row === b.bag_id && editCell?.col === "qty" ? (
                        <input autoFocus value={editVal} onChange={e => setEditVal(e.target.value)}
                          onBlur={() => saveEdit(b)} onKeyDown={e => { if(e.key === "Enter") saveEdit(b); if(e.key === "Escape") setEditCell(null); }}
                          style={{ width: 60, border: "1px solid #3b82f6", borderRadius: 2, padding: "1px 4px", fontSize: 13 }} />
                      ) : <span style={{ fontWeight: 600, cursor: "pointer" }}>{b.current_quantity}</span>}
                    </td>
                    <td style={td}>{b.container_display_name}</td>
                    <td style={td}>{b.layer_name}</td>
                    <td style={td}><button onClick={() => deleteBag(b.bag_id)} style={{ cursor:"pointer", border:"none", background:"none", fontSize:13, color:"#c00" }}>✕</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {view === "containers" && (
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <thead><tr style={{ background: "#f0f0f0", position: "sticky", top: 0 }}>
                <th style={th}>Container ID</th><th style={th}>Name</th><th style={th}>Layer</th><th style={th}>Bags</th>
              </tr></thead>
              <tbody>
                {filteredContainers.map((c, i) => (
                  <tr key={c.id} style={{ background: i % 2 === 0 ? "#fff" : "#f9f9f9" }}>
                    <td style={{...td, fontFamily: "monospace", fontSize: 12}}>{c.id}</td>
                    <td style={td}>{c.display_name}</td>
                    <td style={td}>{layers.find(l => l.id === c.storage_layer_id)?.name || c.storage_layer_id}</td>
                    <td style={td}>{bags.filter(b => b.container_id === c.id).length}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}

const th: React.CSSProperties = { padding: "6px 8px", textAlign: "left", fontSize: 11, fontWeight: 600, borderBottom: "2px solid #d0d0d0", whiteSpace: "nowrap" };
const td: React.CSSProperties = { padding: "3px 8px", borderBottom: "1px solid #e0e0e0", fontSize: 13, verticalAlign: "middle" };
