"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getBoard } from "@/lib/client";
import type { Board } from "@/lib/schemas";
import { BoardView } from "@/components/board";
import { CandidateDrawer } from "@/components/candidate-drawer";
import { Results } from "@/components/results";
import { SearchBar } from "@/components/search-bar";

/**
 * The one screen. The search box sits above, and below it is either the board or the
 * results — never both, because a board that is not filtered by the query above it is a
 * board that contradicts it.
 *
 * <p>The query lives in the URL. That is the same argument the backend made for spelling
 * the looser match as a field rather than a request flag: what produced these results
 * should be visible in, and reproducible from, the thing you can copy. A shared link
 * reopens the same search.
 */
export function Workspace({ initialBoard }: { initialBoard: Board }) {
  const fromUrl =
    typeof window === "undefined" ? "" : (new URLSearchParams(window.location.search).get("q") ?? "");
  // Two pieces of state, not one. `text` is what is in the box this instant; `query` is
  // what has settled and is worth running. Collapsing them would either fire a search per
  // keystroke or lag the input behind her typing.
  const [text, setText] = useState(fromUrl);
  const [query, setQuery] = useState(fromUrl);
  const [openCandidate, setOpenCandidate] = useState<string | null>(null);

  // replaceState rather than the router: the board above came from a server render, and
  // pushing a route change would re-run it on every settled keystroke to produce the same
  // board. The URL still updates, so reload and copy-paste both work.
  useEffect(() => {
    const url = new URL(window.location.href);
    if (query) {
      url.searchParams.set("q", query);
    } else {
      url.searchParams.delete("q");
    }
    window.history.replaceState(null, "", url);
  }, [query]);

  const board = useQuery({ queryKey: ["board"], queryFn: getBoard, initialData: initialBoard });

  return (
    <>
      <SearchBar value={text} onChange={setText} onCommit={setQuery} />

      {query ? (
        // A suggestion only has to put its query in the box; the debounce commits it, so
        // clicking one goes through exactly the path typing it would.
        <Results query={query} onOpenCandidate={setOpenCandidate} onUseQuery={setText} />
      ) : (
        <BoardView initialBoard={initialBoard} onOpenCandidate={setOpenCandidate} />
      )}

      <CandidateDrawer
        candidateId={openCandidate}
        board={board.data}
        onClose={() => setOpenCandidate(null)}
      />
    </>
  );
}
