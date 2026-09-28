import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Explicit, because Testing Library only registers its own afterEach when the test
// globals are injected, and this project keeps them off. Without it every render stays in
// the document and the second test finds two of everything.
afterEach(cleanup);

/**
 * jsdom implements neither, and Base UI's popups ask for both on mount. Without them the
 * tooltips around the interpretation chips throw and take the component down with them,
 * which would look like a failure of the thing actually under test.
 */
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver;

if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {};
}
