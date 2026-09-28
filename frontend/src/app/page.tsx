import { spring } from "@/lib/api";
import { Board } from "@/lib/schemas";
import { Workspace } from "@/components/workspace";

/**
 * Never prerendered. The board is a live view of who is where, so a copy baked at build
 * time would be wrong before it shipped — and the build would need a running backend and
 * the API key to produce it, which is a bad thing to need in order to compile.
 */
export const dynamic = "force-dynamic";

/**
 * The board is fetched here, on the server, so the first paint already has it. That is the
 * one load worth doing this way: it is the screen a recruiter opens on, it needs no
 * interaction to be useful, and fetching it in the browser would mean a spinner on every
 * visit for data the server could have inlined.
 *
 * <p>Everything after this is client-side, because everything after this is a reaction to
 * her typing or clicking.
 */
export default async function Page() {
  const response = await spring("/api/v1/pipeline");
  if (!response.ok) {
    throw new Error(`The pipeline service answered ${response.status}`);
  }
  const board = Board.parse(await response.json());

  return (
    <main className="mx-auto flex min-h-screen max-w-[1600px] flex-col gap-6 p-6">
      <header className="flex items-baseline justify-between">
        <h1 className="text-xl font-semibold tracking-tight">Hiring pipeline</h1>
        <p className="text-sm text-muted-foreground">Backend Engineer</p>
      </header>
      <Workspace initialBoard={board} />
    </main>
  );
}
