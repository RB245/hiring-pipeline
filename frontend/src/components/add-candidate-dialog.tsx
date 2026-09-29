"use client";

import { useState } from "react";
import { ApiError, type NewCandidate } from "@/lib/client";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/**
 * Adding somebody to the pipeline.
 *
 * <p>Four fields and no stage picker: a candidate starts in Applied because that is the
 * only place anyone can start, and offering the choice would be offering a way to be
 * wrong. The pipeline decides; the form only says who.
 *
 * <p>Built as a real {@code <form>} rather than a div with a click handler, which is what
 * makes Enter submit from any field without a keydown handler of our own. The first input
 * is autofocused and the tab order is the source order, so the whole thing is typeable
 * without reaching for a mouse.
 */
export function AddCandidateDialog({
  open,
  onOpenChange,
  onSubmit,
  pending,
  failure,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (candidate: NewCandidate) => void;
  pending: boolean;
  /** The last failure, held by the board so it survives the optimistic insert unwinding. */
  failure: ApiError | null;
}) {
  const [form, setForm] = useState<NewCandidate>({ fullName: "", email: "", phone: "", source: "" });

  function field(name: keyof NewCandidate) {
    return {
      id: name,
      value: form[name] ?? "",
      onChange: (event: React.ChangeEvent<HTMLInputElement>) =>
        setForm((current) => ({ ...current, [name]: event.target.value })),
    };
  }

  // The backend names the field a message belongs against, so a duplicate email is shown
  // under the email box rather than somewhere the eye has to go looking for it.
  const problem = failure?.problem ?? null;
  const emailError = problem?.field === "email" ? problem.detail : null;
  const generalError = problem && problem.field !== "email" ? describe(failure!) : null;

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          setForm({ fullName: "", email: "", phone: "", source: "" });
        }
        onOpenChange(next);
      }}
    >
      <DialogContent className="sm:max-w-md">
        <form
          onSubmit={(event) => {
            event.preventDefault();
            onSubmit({
              fullName: form.fullName.trim(),
              email: form.email.trim(),
              phone: form.phone?.trim() || undefined,
              source: form.source?.trim() || undefined,
            });
          }}
        >
          <DialogHeader>
            <DialogTitle>Add a candidate</DialogTitle>
            <DialogDescription>They start in Applied, like everyone else.</DialogDescription>
          </DialogHeader>

          <div className="flex flex-col gap-3 px-4 py-4">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="fullName">Full name</Label>
              {/* Autofocused: the dialog exists to be typed into, and landing the caret
                  here is what lets her keep talking instead of aiming at a field. */}
              <Input {...field("fullName")} autoFocus required maxLength={200} autoComplete="off" />
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="email">Email</Label>
              <Input
                {...field("email")}
                type="email"
                required
                maxLength={320}
                autoComplete="off"
                aria-invalid={emailError ? true : undefined}
                aria-describedby={emailError ? "email-error" : undefined}
              />
              {emailError && (
                <p id="email-error" role="alert" className="text-sm text-red-600">
                  {emailError}
                </p>
              )}
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="phone">Phone</Label>
              <Input {...field("phone")} maxLength={50} autoComplete="off" />
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="source">Source</Label>
              {/* Free text, not a dropdown. It feeds the source: search field, so whatever
                  is typed here has to be findable by typing the same thing there. */}
              <Input {...field("source")} maxLength={100} autoComplete="off" placeholder="referral" />
            </div>

            {generalError && (
              <p role="alert" className="text-sm text-red-600">
                {generalError}
              </p>
            )}
          </div>

          <DialogFooter>
            <DialogClose render={<Button type="button" variant="outline" />}>Cancel</DialogClose>
            {/* Not "Add candidate" — that is the button that opened this, and two
                controls with one name is ambiguous to anyone navigating by label. */}
            <Button type="submit" disabled={pending}>
              {pending ? "Adding…" : "Add to pipeline"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Anything that is not a duplicate email. The rate limiter is called out by name because
 * it is the one a demo actually hits — twenty writes a minute, and a burst of clicking
 * looks like a broken button unless the reason says otherwise.
 */
function describe(failure: ApiError): string {
  if (failure.status === 429) {
    return "That is more writes than the API allows in a minute. Wait a moment and try again.";
  }
  return failure.problem?.detail ?? "That could not be saved.";
}
