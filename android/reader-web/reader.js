import mermaid from 'mermaid';
import hljs from 'highlight.js/lib/common';
import 'highlight.js/styles/github.css';
import './reader.css';

const blocks = [...document.querySelectorAll('#note [data-block-id]')];
const status = {diagrams: 0, diagramErrors: 0};

async function render() {
  for (const block of blocks) {
    if (block.textContent !== block.dataset.anchorText) throw new Error(`Java/DOM text mismatch in ${block.dataset.blockId}`);
  }
  for (const code of document.querySelectorAll('pre:not([data-mermaid]) code')) {
    const language = [...code.classList].find(name => name.startsWith('language-'))?.slice(9);
    if (language && hljs.getLanguage(language)) hljs.highlightElement(code);
  }
  for (const block of blocks) {
    if (block.textContent !== block.dataset.anchorText) throw new Error(`Highlighting changed canonical text in ${block.dataset.blockId}`);
  }
  mermaid.initialize({startOnLoad: false, securityLevel: 'strict', htmlLabels: false,
    maxTextSize: Number(document.body.dataset.maxDiagramChars), maxEdges: Number(document.body.dataset.maxEdges),
    suppressErrorRendering: true,
    secure: ['securityLevel', 'startOnLoad', 'htmlLabels', 'maxTextSize', 'maxEdges', 'flowchart']});
  for (const source of document.querySelectorAll('pre[data-mermaid]')) {
    const diagram = document.createElement('div');
    diagram.className = 'diagram';
    source.before(diagram);
    const details = document.createElement('details');
    const summary = document.createElement('summary');
    summary.textContent = 'Mermaid source';
    details.append(summary);
    source.before(details);
    details.append(source);
    // This boundary handles Mermaid's errors for untrusted diagram input; no alternate renderer is attempted.
    let svg;
    try {
      ({svg} = await mermaid.render(`diagram-${source.dataset.blockId}`, source.dataset.anchorText));
    } catch (error) {
      status.diagramErrors++;
      diagram.className = 'diagram-error';
      diagram.textContent = `Diagram ${source.dataset.blockId} could not be rendered. Its source is available below.`;
      console.error(`Mermaid failed for ${source.dataset.blockId}: ${error?.name || 'diagram-input-error'}`);
      continue;
    }
    diagram.innerHTML = svg;
    status.diagrams++;
  }
  document.getElementById('render-status').textContent =
    `${blocks.length} canonical blocks; ${status.diagrams} diagrams; ${status.diagramErrors} diagram errors`;
}

// Repository images come from the app's cache or the authenticated backend; when neither has one, say so in place.
for (const image of document.querySelectorAll('#note img')) {
  const unavailable = () => {
    const label = document.createElement('span');
    label.className = 'image-blocked';
    label.textContent = `[Image unavailable: ${image.alt}]`;
    image.replaceWith(label);
  };
  if (image.complete && image.naturalWidth === 0) unavailable();
  else image.addEventListener('error', unavailable, {once: true});
}

function headingPath(block) {
  const path = [];
  for (let level = 1; level <= 6; level++) {
    const heading = block.getAttribute(`data-heading-${level}`);
    if (heading !== null) path.push(heading);
  }
  return path;
}

const PREFIX_CHARS = 64;

function scrollPercent() {
  const scrollable = document.documentElement.scrollHeight - window.innerHeight;
  return scrollable <= 0 ? 100 : Math.max(0, Math.min(100, Math.round(window.scrollY / scrollable * 100)));
}

/*
 * Reading position for the native app, which calls these through evaluateJavascript; the page has no way to call native
 * code. The anchor is the first block still visible at the top of the viewport.
 */
window.reporead = {
  position() {
    const index = blocks.findIndex(block => block.getBoundingClientRect().bottom > 0);
    const block = index < 0 ? null : blocks[index];
    return {
      progressPercent: scrollPercent(),
      anchor: {
        headingPath: block ? headingPath(block) : [],
        textPrefix: block ? block.dataset.anchorText.slice(0, PREFIX_CHARS) : null,
        blockIndex: Math.max(index, 0),
      },
    };
  },
  /** Restores by heading and text, then text alone, then section, then block index, then percent. Returns how. */
  restore(anchor, progressPercent) {
    const section = JSON.stringify(anchor.headingPath);
    const sameText = block => anchor.textPrefix !== null && block.dataset.anchorText.slice(0, PREFIX_CHARS) === anchor.textPrefix;
    const inSection = blocks.filter(block => JSON.stringify(headingPath(block)) === section);
    const nearest = candidates => candidates.reduce((best, block) =>
      Math.abs(blocks.indexOf(block) - anchor.blockIndex) < Math.abs(blocks.indexOf(best) - anchor.blockIndex) ? block : best);
    let target = null;
    let mode;
    const exact = inSection.filter(sameText);
    const anywhere = blocks.filter(sameText);
    if (exact.length) [target, mode] = [nearest(exact), 'exact'];
    else if (anywhere.length) [target, mode] = [nearest(anywhere), 'text'];
    else if (inSection.length) [target, mode] = [inSection[0], 'section'];
    else if (anchor.blockIndex < blocks.length) [target, mode] = [blocks[anchor.blockIndex], 'block'];
    else mode = 'percent';
    if (target) target.scrollIntoView({block: 'start'});
    else window.scrollTo(0, progressPercent / 100 * (document.documentElement.scrollHeight - window.innerHeight));
    return mode;
  },
};

/*
 * Selection capture under the Stage 0 contract: one block, UTF-16 offsets into its canonical (Java-exported) text.
 * Broken mapping is an invariant failure, never an approximate anchor.
 */
function capture() {
  const selection = window.getSelection();
  if (!selection || selection.isCollapsed || selection.rangeCount !== 1) return null;
  const range = selection.getRangeAt(0);
  const elementFor = node => node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
  const startBlock = elementFor(range.startContainer).closest('[data-block-id]');
  const endBlock = elementFor(range.endContainer).closest('[data-block-id]');
  if (!startBlock || startBlock !== endBlock || !blocks.includes(startBlock)) {
    return {error: 'Select text within a single paragraph, heading, list item, table cell, or code block.'};
  }
  const before = document.createRange();
  before.selectNodeContents(startBlock);
  before.setEnd(range.startContainer, range.startOffset);
  const startOffset = before.toString().length;
  before.setEnd(range.endContainer, range.endOffset);
  const endOffset = before.toString().length;
  const exactText = range.toString();
  if (startBlock.textContent !== startBlock.dataset.anchorText ||
      startBlock.dataset.anchorText.slice(startOffset, endOffset) !== exactText) {
    throw new Error(`Canonical selection invariant failed in ${startBlock.dataset.blockId}`);
  }
  if (!exactText.length) return null;
  return {sourceBlobSha: document.body.dataset.sourceBlobSha, blockId: startBlock.dataset.blockId, startOffset, endOffset, exactText};
}

/** Wraps [start, end) of a block's canonical text in <mark> elements; text content is unchanged. */
function wrap(block, start, end, key) {
  const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
  const pieces = [];
  let offset = 0;
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const length = node.data.length;
    const from = Math.max(start, offset);
    const to = Math.min(end, offset + length);
    if (from < to) pieces.push([node, from - offset, to - offset]);
    offset += length;
  }
  for (const [node, from, to] of pieces) {
    let target = node;
    if (to < target.data.length) target.splitText(to);
    if (from > 0) target = target.splitText(from);
    const mark = document.createElement('mark');
    mark.dataset.key = key;
    target.replaceWith(mark);
    mark.append(target);
  }
}

window.reporead.capture = capture;

/** Replaces all highlights. Returns the keys whose text is not in this version, which are not drawn. */
window.reporead.highlight = annotations => {
  for (const mark of document.querySelectorAll('#note mark[data-key]')) mark.replaceWith(...mark.childNodes);
  for (const block of blocks) block.normalize();
  const missing = [];
  for (const {key, blockId, startOffset, endOffset, exactText} of annotations) {
    const block = blocks.find(candidate => candidate.dataset.blockId === blockId);
    if (!block || block.dataset.anchorText.slice(startOffset, endOffset) !== exactText) {
      missing.push(key);
      continue;
    }
    wrap(block, startOffset, endOffset, key);
  }
  for (const block of blocks) {
    if (block.textContent !== block.dataset.anchorText) throw new Error(`Highlighting changed canonical text in ${block.dataset.blockId}`);
  }
  return missing;
};

window.reporead.reveal = key => {
  const mark = document.querySelector(`#note mark[data-key="${CSS.escape(key)}"]`);
  if (!mark) return false;
  mark.scrollIntoView({block: 'center'});
  mark.classList.add('revealed');
  setTimeout(() => mark.classList.remove('revealed'), 1500);
  return true;
};

// Diagrams change the layout, so the app restores a position only once rendering has finished.
render().then(() => {
  document.body.dataset.state = 'ready';
}, error => {
  document.body.dataset.state = 'failed';
  document.getElementById('render-status').textContent = `Reader failed: ${error.message}`;
  console.error(error);
});
