/**
 * Renders docs/architecture.pdf from docs/architecture.md.
 *
 * The diagrams stay Mermaid in a markdown file that is the version-controlled source; the
 * PDF is a build output. mermaid-cli turns the code blocks into SVGs in one pass, marked
 * turns the prose into HTML, and Chrome prints it. Nothing here is hand-placed, so the
 * document cannot drift from the diagrams the way a folder of exported PNGs does.
 *
 *   cd docs/tooling && npm install && npm run pdf
 *
 * Needs a Chrome on the machine. Set CHROME if it is somewhere unusual.
 */
import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { marked } from "marked";
import puppeteer from "puppeteer-core";

const HERE = dirname(fileURLToPath(import.meta.url));
const SOURCE = resolve(HERE, "..", "architecture.md");
const OUTPUT = resolve(HERE, "..", "architecture.pdf");

const CHROMES = [
  process.env.CHROME,
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "/usr/bin/google-chrome",
  "/usr/bin/chromium",
].filter(Boolean);

function findChrome() {
  const found = CHROMES.find((path) => existsSync(path));
  if (!found) {
    throw new Error(`No Chrome found. Tried:\n  ${CHROMES.join("\n  ")}\nSet CHROME to override.`);
  }
  return found;
}

/**
 * One mermaid-cli pass over the whole file. Given markdown it rewrites each code block
 * into an image reference and writes the SVGs beside it, which is a single Chrome launch
 * rather than one per diagram.
 */
function renderDiagrams(chrome, work) {
  const input = join(work, "architecture.md");
  const output = join(work, "rendered.md");
  writeFileSync(input, readFileSync(SOURCE, "utf8"));
  writeFileSync(
    join(work, "config.json"),
    JSON.stringify({ theme: "neutral", themeVariables: { fontFamily: "Georgia, serif" } }),
  );
  // The CLI's own entry point, run on this Node, rather than through npx. Node refuses
  // to spawn a .cmd shim directly on Windows, and pinning the dependency here means the
  // PDF renders from the version in the lockfile rather than whatever npx resolves today.
  const mmdc = join(HERE, "node_modules", "@mermaid-js", "mermaid-cli", "src", "cli.js");
  execFileSync(
    process.execPath,
    [mmdc, "-i", input, "-o", output, "-c", join(work, "config.json"), "-b", "white"],
    {
      stdio: "inherit",
      env: { ...process.env, PUPPETEER_SKIP_DOWNLOAD: "1", PUPPETEER_EXECUTABLE_PATH: chrome },
    },
  );
  return readFileSync(output, "utf8");
}

/** Inlined, because a PDF cannot follow a relative path to an SVG after it is written. */
function inlineImages(markdown, work) {
  return markdown.replace(/!\[[^\]]*\]\(\.?\/?([^)]+\.svg)\)/g, (whole, file) => {
    const path = join(work, file);
    if (!existsSync(path)) {
      return whole;
    }
    const svg = readFileSync(path).toString("base64");
    return `<img class="diagram" src="data:image/svg+xml;base64,${svg}" />`;
  });
}

const CSS = `
  /*
   * Both of these, explicitly. Chrome honours the machine's dark-mode preference when a
   * document does not state one, so a page that sets a text colour and no background
   * printed dark grey on black — legible on screen while writing it, unreadable as a PDF.
   */
  :root { color-scheme: only light; }
  html, body { background: #ffffff; }

  @page { size: A4; margin: 18mm 16mm; }
  body { font: 10.5pt/1.55 Georgia, "Times New Roman", serif; color: #1a1a1a; }
  h1 { font-size: 21pt; margin: 0 0 .2em; }
  h2 { font-size: 14pt; margin: 1.6em 0 .5em; padding-bottom: .2em; border-bottom: 1px solid #ddd;
       page-break-after: avoid; }
  h1 + p { color: #555; font-style: italic; }
  code { font: 9pt/1.4 "Cascadia Mono", Consolas, monospace; background: #f4f4f4;
         padding: .1em .3em; border-radius: 3px; }
  table { border-collapse: collapse; width: 100%; font-size: 9pt; margin: .8em 0; }
  th, td { border: 1px solid #ddd; padding: .35em .6em; text-align: left; }
  th { background: #f4f4f4; }
  img.diagram { display: block; max-width: 100%; max-height: 165mm; margin: 1em auto; }
  hr { border: 0; border-top: 1px solid #e0e0e0; margin: 1.6em 0; }
  a { color: #0b5cad; }
  /* A diagram split across a page break is worse than a short page. */
  p, ul, table, img { page-break-inside: avoid; }
`;

async function main() {
  const chrome = findChrome();
  const work = mkdtempSync(join(tmpdir(), "pipeline-pdf-"));
  try {
    const html = `<!doctype html><meta charset="utf-8"><style>${CSS}</style>${marked.parse(
      inlineImages(renderDiagrams(chrome, work), work),
    )}`;

    const browser = await puppeteer.launch({ executablePath: chrome, headless: "shell", args: ["--no-sandbox"] });
    const page = await browser.newPage();
    await page.setContent(html, { waitUntil: "load" });
    await page.pdf({
      path: OUTPUT,
      format: "A4",
      printBackground: true,
      margin: { top: "18mm", bottom: "18mm", left: "16mm", right: "16mm" },
      displayHeaderFooter: true,
      headerTemplate: "<span></span>",
      footerTemplate:
        '<div style="width:100%;font:8pt Georgia,serif;color:#888;padding:0 16mm;text-align:right;">' +
        'Hiring pipeline — architecture · <span class="pageNumber"></span>/<span class="totalPages"></span></div>',
    });
    await browser.close();
    console.log(`Wrote ${OUTPUT}`);
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
}

await main();
