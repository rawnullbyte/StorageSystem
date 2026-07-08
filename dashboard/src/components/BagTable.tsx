import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { updateQuantity } from "@/lib/api";
import { useStore } from "@/store";
import type { BagWithDetails } from "@/lib/api";

interface BagTableProps {
  filterContainerId?: string;
}

export function BagTable({ filterContainerId }: BagTableProps) {
  const bags = useStore((s) => s.bags);
  const [editRow, setEditRow] = useState<{ containerId: string; partNumber: string } | null>(null);
  const [editValue, setEditValue] = useState("");

  const filtered = filterContainerId
    ? bags.filter((b) => b.container_id === filterContainerId)
    : bags;

  async function handleSave(b: BagWithDetails) {
    try {
      await updateQuantity({
        container_id: b.container_id,
        lcsc_part_number: b.lcsc_part_number,
        quantity: parseInt(editValue, 10),
      });
      setEditRow(null);
    } catch (err) {
      console.error("Failed to update quantity", err);
    }
  }

  if (filtered.length === 0) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Component Bags</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-muted-foreground">No bags registered yet. Scan a bag QR code to add one.</p>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Component Bags {filterContainerId ? "(filtered)" : ""}</CardTitle>
        <p className="text-sm text-muted-foreground">
          {filtered.length} bag{filtered.length !== 1 ? "s" : ""} found
        </p>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>LCSC Part #</TableHead>
              <TableHead>Manufacturer Part #</TableHead>
              <TableHead>Container</TableHead>
              <TableHead>Layer</TableHead>
              <TableHead>Quantity</TableHead>
              <TableHead>Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {filtered.map((b) => (
              <TableRow key={`${b.container_id}-${b.lcsc_part_number}`}>
                <TableCell className="font-mono text-xs">{b.lcsc_part_number}</TableCell>
                <TableCell className="font-mono text-xs">{b.mfg_part_number || "—"}</TableCell>
                <TableCell>{b.container_display_name}</TableCell>
                <TableCell>
                  <Badge variant="secondary">{b.layer_name}</Badge>
                </TableCell>
                <TableCell>
                  {editRow?.containerId === b.container_id &&
                  editRow?.partNumber === b.lcsc_part_number ? (
                    <div className="flex items-center gap-2">
                      <Input
                        type="number"
                        className="w-24 h-8"
                        value={editValue}
                        onChange={(e) => setEditValue(e.target.value)}
                        min={0}
                      />
                      <Button size="sm" onClick={() => handleSave(b)}>
                        Save
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => setEditRow(null)}>
                        Cancel
                      </Button>
                    </div>
                  ) : (
                    <span className="font-semibold">{b.current_quantity}</span>
                  )}
                </TableCell>
                <TableCell>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => {
                      setEditRow({
                        containerId: b.container_id,
                        partNumber: b.lcsc_part_number,
                      });
                      setEditValue(String(b.current_quantity));
                    }}
                  >
                    Adjust Stock
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}
