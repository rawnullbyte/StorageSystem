import { Badge } from "@/components/ui/badge";
import { useStore } from "@/store";

export function ConnectionStatus() {
  const status = useStore((s) => s.wsStatus);

  const colorMap: Record<string, string> = {
    connected: "bg-green-500",
    connecting: "bg-yellow-500",
    disconnected: "bg-red-500",
  };

  const labelMap: Record<string, string> = {
    connected: "Live Synchronization Active",
    connecting: "Connecting…",
    disconnected: "Disconnected",
  };

  return (
    <div className="flex items-center gap-2">
      <span className={`h-2.5 w-2.5 rounded-full ${colorMap[status] || "bg-gray-500"}`} />
      <Badge variant="outline" className="text-xs font-normal">
        {labelMap[status] || "Unknown"}
      </Badge>
    </div>
  );
}
