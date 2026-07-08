import { useEffect, useState, useCallback } from "react";
import { BagTable } from "@/components/BagTable";
import { ConnectionStatus } from "@/components/ConnectionStatus";
import { LayerSidebar } from "@/components/LayerSidebar";
import { getBags, getContainers, getLayers } from "@/lib/api";
import { useWebSocket, type WsEventMessage } from "@/hooks/use-websocket";
import { useStore } from "@/store";

export default function App() {
  const [selectedContainerId, setSelectedContainerId] = useState<string | null>(null);
  const setBags = useStore((s) => s.setBags);
  const setWsStatus = useStore((s) => s.setWsStatus);
  const setLayers = useStore((s) => s.setLayers);
  const setContainers = useStore((s) => s.setContainers);

  // Handle WebSocket events
  const handleWsEvent = useCallback(
    (event: WsEventMessage) => {
      if (event.type === "BagAdded") {
        // Refresh full list to get details
        getBags().then(setBags).catch(console.error);
      } else if (event.type === "QuantityUpdated") {
        // Optimistic local update + refresh
        useStore.getState().updateBagQuantity(
          event.payload.container_id,
          event.payload.lcsc_part_number,
          event.payload.new_quantity
        );
      }
    },
    [setBags]
  );

  const wsStatus = useWebSocket(handleWsEvent);

  // Sync WS status to store
  useEffect(() => {
    setWsStatus(wsStatus);
  }, [wsStatus, setWsStatus]);

  // Initial data load
  useEffect(() => {
    Promise.all([getBags(), getContainers(), getLayers()])
      .then(([bags, containers, layers]) => {
        setBags(bags);
        setContainers(containers);
        setLayers(layers);
      })
      .catch(console.error);
  }, []);

  return (
    <div className="min-h-screen bg-background">
      {/* Top bar */}
      <header className="border-b bg-card px-6 py-3 flex items-center justify-between">
        <div className="flex items-center gap-4">
          <h1 className="text-xl font-bold tracking-tight">StorageSystem</h1>
          <span className="text-sm text-muted-foreground hidden sm:inline">
            High-Density Inventory Tracking
          </span>
        </div>
        <ConnectionStatus />
      </header>

      {/* Main content */}
      <div className="flex h-[calc(100vh-57px)]">
        {/* Sidebar */}
        <aside className="w-72 border-r overflow-y-auto hidden md:block">
          <LayerSidebar
            selectedContainerId={selectedContainerId}
            onSelectContainer={setSelectedContainerId}
          />
        </aside>

        {/* Main panel */}
        <main className="flex-1 p-6 overflow-y-auto">
          <BagTable filterContainerId={selectedContainerId || undefined} />
        </main>
      </div>
    </div>
  );
}
