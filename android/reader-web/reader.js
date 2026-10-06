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

render().catch(error => {
  document.getElementById('render-status').textContent = `Reader failed: ${error.message}`;
  console.error(error);
});
