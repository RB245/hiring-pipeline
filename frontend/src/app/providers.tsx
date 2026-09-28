"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/components/ui/tooltip";
import { useState } from "react";

export function Providers({ children }: { children: React.ReactNode }) {
  // Created in state rather than at module scope: a module-level client would be shared
  // between requests on the server and leak one user's cache into another's render.
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            // The board is handed to the client already populated by the server render, so
            // an immediate refetch on mount would throw that away and flash.
            staleTime: 10_000,
            retry: false,
            refetchOnWindowFocus: false,
          },
        },
      }),
  );

  return (
    <QueryClientProvider client={client}>
      <TooltipProvider delay={150}>{children}</TooltipProvider>
    </QueryClientProvider>
  );
}
