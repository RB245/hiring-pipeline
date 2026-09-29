/**
 * Regenerates the demo GIF in the README by driving the running application.
 *
 * Committed rather than the GIF alone, for the same reason the architecture diagrams are
 * Mermaid sources rather than pasted images: an artefact nobody can regenerate goes stale
 * silently, and the first thing a reviewer sees is the worst place for that.
 *
 *   docker compose up -d        # the app it records has to be running
 *   cd docs/tooling && npm install && npm run record
 *
 * Needs a Chrome on the machine (puppeteer-core, so none is downloaded). Set CHROME to
 * point at one if it is somewhere unusual.
 */
import { existsSync, writeFileSync } from "node:fs";
import { PNG } from "pngjs";
// gifenc ships CommonJS, so the named exports come off the default.
import gifenc from "gifenc";
import puppeteer from "puppeteer-core";

const { GIFEncoder, applyPalette, quantize } = gifenc;

const APP = process.env.APP_URL ?? "http://localhost:3000";
const OUT = new URL("../demo.gif", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const WIDTH = 1360;
const HEIGHT = 760;
const FRAME_MS = 420;

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

const frames = [];

/**
 * `hold` is a dwell time, not a repeat count. Writing the same screenshot several times
 * to linger on it tripled the file for no extra information; one frame with a longer
 * delay looks identical and costs a third as much.
 */
async function shoot(page, hold = 1) {
  frames.push({ png: await page.screenshot({ type: "png" }), hold });
}

/** Typed in small runs so the GIF shows the box filling rather than jumping. */
async function typeSlowly(page, selector, text, run = 4) {
  for (let at = 0; at < text.length; at += run) {
    await page.type(selector, text.slice(at, at + run), { delay: 12 });
    await shoot(page);
  }
}

async function clear(page, selector) {
  await page.click(selector, { clickCount: 3 });
  await page.keyboard.press("Backspace");
}

const BOX = 'input[role="combobox"]';

async function record() {
  const browser = await puppeteer.launch({
    executablePath: findChrome(),
    headless: "shell",
    args: ["--no-sandbox", "--force-device-scale-factor=1", "--hide-scrollbars"],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: WIDTH, height: HEIGHT, deviceScaleFactor: 1 });

  // The board, server-rendered, before anything is typed.
  await page.goto(APP, { waitUntil: "networkidle2" });
  await page.waitForSelector("[data-card]");
  await shoot(page, 5);

  // A sentence, not a query language. The chips below the box are the point: she can see
  // she was understood before she trusts the results.
  await page.click(BOX);
  await typeSlowly(page, BOX, "Who has been stuck in Screening for more than a week?");
  await page.waitForSelector("text/Stage = Screening", { timeout: 15000 }).catch(() => {});
  await new Promise((r) => setTimeout(r, 1200));
  await shoot(page, 7);

  // A misspelling: the span the API returned, underlined, with the correction as a chip.
  await clear(page, BOX);
  await typeSlowly(page, BOX, "stage:Intervew");
  await new Promise((r) => setTimeout(r, 1400));
  await shoot(page, 7);

  // Accepting the correction rewrites the query in place, over the span that was wrong.
  const chip = await page.$('[role="alert"] button');
  if (chip) {
    await chip.click();
    await new Promise((r) => setTimeout(r, 1400));
    await shoot(page, 7);
  }

  // A typo trigrams cannot see, and the suggestion that rescues it.
  await clear(page, BOX);
  await typeSlowly(page, BOX, "pryia");
  await new Promise((r) => setTimeout(r, 1600));
  await shoot(page, 8);

  // Back to the board, and a candidate moved without a reload.
  await clear(page, BOX);
  await page.waitForSelector("[data-card]");
  await new Promise((r) => setTimeout(r, 600));
  await shoot(page, 4);

  const advance = await page.$('section button.h-7');
  if (advance) {
    await advance.click();
    await new Promise((r) => setTimeout(r, 500));
    await shoot(page, 3);
    await new Promise((r) => setTimeout(r, 900));
    await shoot(page, 6);
  }

  await browser.close();
}

function encode() {
  const gif = GIFEncoder();
  for (const { png, hold } of frames) {
    const { data, width, height } = PNG.sync.read(Buffer.from(png));
    // 48 colours is plenty for a flat UI of text on white, and roughly halves the file
    // against the 256 a photograph would need.
    const palette = quantize(data, 48, { format: "rgb565" });
    gif.writeFrame(applyPalette(data, palette, "rgb565"), width, height, {
      palette,
      delay: FRAME_MS * hold,
    });
  }
  gif.finish();
  writeFileSync(OUT, Buffer.from(gif.bytes()));
  const mb = (gif.bytes().length / 1024 / 1024).toFixed(2);
  console.log(`${frames.length} frames -> ${OUT} (${mb} MB)`);
}

await record();
encode();
