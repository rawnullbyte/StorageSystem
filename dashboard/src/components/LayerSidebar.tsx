import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  DialogDescription,
  DialogClose,
} from "@/components/ui/dialog";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { getLayers, createLayer, getContainers, createContainer, type Container } from "@/lib/api";
import { useStore } from "@/store";
import { Badge } from "@/components/ui/badge";

interface LayerSidebarProps {
  selectedContainerId: string | null;
  onSelectContainer: (id: string | null) => void;
}

export function LayerSidebar({ selectedContainerId, onSelectContainer }: LayerSidebarProps) {
  const layers = useStore((s) => s.layers);
  const containers = useStore((s) => s.containers);
  const setLayers = useStore((s) => s.setLayers);
  const setContainers = useStore((s) => s.setContainers);

  const [addLayerOpen, setAddLayerOpen] = useState(false);
  const [addContainerOpen, setAddContainerOpen] = useState(false);
  const [newLayerName, setNewLayerName] = useState("");
  const [newContainerName, setNewContainerName] = useState("");
  const [newContainerLayer, setNewContainerLayer] = useState("");

  async function handleCreateLayer() {
    if (!newLayerName.trim()) return;
    try {
      await createLayer(newLayerName.trim());
      const updated = await getLayers();
      setLayers(updated);
      setNewLayerName("");
      setAddLayerOpen(false);
    } catch (err) {
      console.error("Failed to create layer", err);
    }
  }

  async function handleCreateContainer() {
    if (!newContainerName.trim() || !newContainerLayer) return;
    try {
      await createContainer(newContainerName.trim(), parseInt(newContainerLayer, 10));
      const updated = await getContainers();
      setContainers(updated);
      setNewContainerName("");
      setNewContainerLayer("");
      setAddContainerOpen(false);
    } catch (err) {
      console.error("Failed to create container", err);
    }
  }

  // Group containers by layer
  const grouped = new Map<number, Container[]>();
  for (const c of containers) {
    const list = grouped.get(c.storage_layer_id) || [];
    list.push(c);
    grouped.set(c.storage_layer_id, list);
  }

  return (
    <Card className="h-full">
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <CardTitle className="text-lg">Layers & Containers</CardTitle>
          <div className="flex gap-1">
            <Dialog open={addLayerOpen} onOpenChange={setAddLayerOpen}>
              <DialogTrigger asChild>
                <Button size="sm" variant="outline">+ Layer</Button>
              </DialogTrigger>
              <DialogContent>
                <DialogHeader>
                  <DialogTitle>Add Layer</DialogTitle>
                  <DialogDescription>Create a new storage layer (shelf, drawer, rack).</DialogDescription>
                </DialogHeader>
                <div className="flex flex-col gap-4 py-4">
                  <Input
                    placeholder="Layer name (e.g. Shelf 2)"
                    value={newLayerName}
                    onChange={(e) => setNewLayerName(e.target.value)}
                  />
                  <DialogClose asChild>
                    <Button onClick={handleCreateLayer}>Create</Button>
                  </DialogClose>
                </div>
              </DialogContent>
            </Dialog>
            <Dialog open={addContainerOpen} onOpenChange={setAddContainerOpen}>
              <DialogTrigger asChild>
                <Button size="sm" variant="outline">+ Container</Button>
              </DialogTrigger>
              <DialogContent>
                <DialogHeader>
                  <DialogTitle>Add Container</DialogTitle>
                  <DialogDescription>Add a new physical bin or drawer to a layer.</DialogDescription>
                </DialogHeader>
                <div className="flex flex-col gap-4 py-4">
                  <Input
                    placeholder="Display name"
                    value={newContainerName}
                    onChange={(e) => setNewContainerName(e.target.value)}
                  />
                  <Select value={newContainerLayer} onValueChange={setNewContainerLayer}>
                    <SelectTrigger>
                      <SelectValue placeholder="Select layer" />
                    </SelectTrigger>
                    <SelectContent>
                      {layers.map((l) => (
                        <SelectItem key={l.id} value={String(l.id)}>
                          {l.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <DialogClose asChild>
                    <Button onClick={handleCreateContainer}>Create</Button>
                  </DialogClose>
                </div>
              </DialogContent>
            </Dialog>
          </div>
        </div>
      </CardHeader>
      <CardContent className="overflow-auto max-h-[calc(100vh-12rem)]">
        {layers.length === 0 && (
          <p className="text-sm text-muted-foreground">No layers yet. Create one to get started.</p>
        )}
        {layers.map((layer) => {
          const layerContainers = grouped.get(layer.id) || [];
          const isSelected = layerContainers.some((c) => c.id === selectedContainerId);
          return (
            <div key={layer.id} className="mb-3">
              <div
                className={`flex items-center justify-between rounded px-2 py-1 text-sm font-medium cursor-pointer hover:bg-muted ${
                  isSelected ? "bg-muted" : ""
                }`}
                onClick={() => onSelectContainer(null)}
              >
                <span>{layer.name}</span>
                <Badge variant="outline" className="text-xs">
                  {layerContainers.length}
                </Badge>
              </div>
              <div className="ml-3 mt-1 space-y-0.5">
                {layerContainers.map((c) => (
                  <div
                    key={c.id}
                    className={`rounded px-2 py-1 text-xs cursor-pointer hover:bg-muted flex items-center justify-between ${
                      selectedContainerId === c.id ? "bg-accent text-accent-foreground font-medium" : ""
                    }`}
                    onClick={() => onSelectContainer(c.id === selectedContainerId ? null : c.id)}
                  >
                    <span>{c.display_name}</span>
                    <span className="font-mono text-muted-foreground" title={c.id}>
                      {c.id.substring(0, 8)}…
                    </span>
                  </div>
                ))}
              </div>
            </div>
          );
        })}
      </CardContent>
    </Card>
  );
}
