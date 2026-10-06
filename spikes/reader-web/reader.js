import mermaid from 'mermaid';
import hljs from 'highlight.js/lib/common';
import 'highlight.js/styles/github.css';
import './reader.css';

const blocks = [...document.querySelectorAll('#note [data-block-id]')];
const status = {state: 'rendering', blocks: blocks.length, diagrams: 0, diagramErrors: 0, canonicalText: false};
let lastSelection = null;

function captureRange() {
  const selection = window.getSelection();
  if (!selection || selection.isCollapsed || selection.rangeCount !== 1) return null;
  const range = selection.getRangeAt(0);
  const elementFor = node => node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
  const startBlock = elementFor(range.startContainer).closest('[data-block-id]');
  const endBlock = elementFor(range.endContainer).closest('[data-block-id]');
  if (!startBlock || startBlock !== endBlock || !blocks.includes(startBlock)) {
    return {error: 'Select text within one marked block for this spike.'};
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
  const headingPath = [];
  for (let level = 1; level <= 6; level++) {
    const heading = startBlock.getAttribute(`data-heading-${level}`);
    if (heading !== null) headingPath.push(heading);
  }
  return {sourceBlobSha: document.body.dataset.sourceBlobSha, blockId: startBlock.dataset.blockId,
    exactText, startOffset, endOffset, headingPath, offsetUnit: 'UTF-16'};
}

document.addEventListener('selectionchange', () => {
  const captured = captureRange();
  if (captured !== null) lastSelection = captured;
});

// DOM checks complement, but do not replace, real touch selection on the phone.
function runChecks() {
  const selection = window.getSelection();
  const checks = [];
  const select = range => { selection.removeAllRanges(); selection.addRange(range); };
  selection.removeAllRanges();
  if (captureRange() !== null) throw new Error('Empty selection must have no anchor');
  checks.push('empty selection');
  for (const block of blocks) {
    const range = document.createRange();
    range.selectNodeContents(block);
    select(range);
    const captured = captureRange();
    if (block.textContent.length && (!captured || captured.exactText !== block.dataset.anchorText ||
        captured.startOffset !== 0 || captured.endOffset !== block.dataset.anchorText.length)) {
      throw new Error(`Full-block check failed in ${block.dataset.blockId}`);
    }
  }
  checks.push(`${blocks.length} canonical blocks`);
  if (blocks.length >= 2) {
    const range = document.createRange();
    range.setStart(blocks[0], 0);
    range.setEnd(blocks[1], blocks[1].childNodes.length);
    select(range);
    if (!captureRange()?.error) throw new Error('Cross-block selection must be rejected');
    checks.push('cross-block rejection');
  }
  selection.removeAllRanges();
  lastSelection = null;
  return {checks, status: {...status}, touchSelectionVerified: false};
}

window.readerProof = {status, capture: () => lastSelection, runChecks};

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
  status.canonicalText = true;
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
  status.state = status.diagramErrors ? 'diagram-errors' : 'ready';
  document.getElementById('render-status').textContent =
    `${blocks.length} canonical blocks; ${status.diagrams} diagrams; ${status.diagramErrors} diagram errors`;
}

render().catch(error => {
  status.state = 'failed';
  document.getElementById('render-status').textContent = `Reader failed: ${error.message}`;
  console.error(error);
});
