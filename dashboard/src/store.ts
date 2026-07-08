/**
 * Zustand store for global dashboard state.
 * Manages layers, containers, bags, and WebSocket connection status.
 */
import { create } from "zustand";
import type { BagWithDetails, Container, StorageLayer } from "./lib/api";
import type { WsStatus } from "./hooks/use-websocket";

interface AppStore {
  // Connection
  wsStatus: WsStatus;
  setWsStatus: (status: WsStatus) => void;

  // Data
  layers: StorageLayer[];
  containers: Container[];
  bags: BagWithDetails[];

  // Actions
  setLayers: (layers: StorageLayer[]) => void;
  setContainers: (containers: Container[]) => void;
  setBags: (bags: BagWithDetails[]) => void;

  // Derived helpers
  getContainersForLayer: (layerId: number) => Container[];
  updateBagQuantity: (containerId: string, partNumber: string, newQuantity: number) => void;
}

export const useStore = create<AppStore>((set, get) => ({
  wsStatus: "disconnected",
  setWsStatus: (status) => set({ wsStatus: status }),

  layers: [],
  containers: [],
  bags: [],

  setLayers: (layers) => set({ layers }),
  setContainers: (containers) => set({ containers }),
  setBags: (bags) => set({ bags }),

  getContainersForLayer: (layerId) =>
    get().containers.filter((c) => c.storage_layer_id === layerId),

  updateBagQuantity: (containerId, partNumber, newQuantity) =>
    set((state) => ({
      bags: state.bags.map((b) =>
        b.container_id === containerId && b.lcsc_part_number === partNumber
          ? { ...b, current_quantity: newQuantity }
          : b
      ),
    })),
}));
