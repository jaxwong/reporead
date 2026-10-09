import mermaid from 'mermaid';
import hljs from 'highlight.js/lib/common';
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
  const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
  mermaid.initialize({startOnLoad: false, securityLevel: 'strict', htmlLabels: false, theme: dark ? 'dark' : 'default',
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
  addCodeTools();
  addFigureTools();
  // The status line is for problems only; a note that rendered fully shows nothing here.
  document.getElementById('render-status').textContent = status.diagramErrors === 0 ? ''
    : `${status.diagramErrors} of ${status.diagrams + status.diagramErrors} diagrams could not be rendered; their sources are shown.`;
}

/*
 * Each code block gets a row of tools above it — outside the block, whose text must stay canonical. Copy is a
 * same-origin link the app intercepts (/copy-code, as /note-link), which then reads the block's text through
 * codeText(): the page still cannot call native code. Wrap, shown only while the code is wider than the screen, is
 * layout only.
 */
function addCodeTools() {
  const toggles = [];
  for (const pre of document.querySelectorAll('#note pre[data-block-id]:not([data-mermaid])')) {
    const tools = document.createElement('div');
    tools.className = 'code-tools';
    const language = [...(pre.querySelector('code')?.classList || [])].find(name => name.startsWith('language-'))?.slice(9);
    const label = document.createElement('span');
    label.textContent = language || '';
    const wrap = document.createElement('button');
    wrap.textContent = 'Wrap';
    wrap.addEventListener('click', () => {
      wrap.textContent = pre.classList.toggle('wrapped') ? 'No wrap' : 'Wrap';
    });
    const copy = document.createElement('a');
    copy.href = `/copy-code?block=${encodeURIComponent(pre.dataset.blockId)}`;
    copy.textContent = 'Copy';
    tools.append(label, wrap, copy);
    pre.before(tools);
    toggles.push([pre, wrap]);
  }
  const showWraps = () => {
    for (const [pre, wrap] of toggles) wrap.hidden = !pre.classList.contains('wrapped') && pre.scrollWidth <= pre.clientWidth;
  };
  showWraps();
  window.addEventListener('resize', showWraps);
}

/*
 * Tables wider than the screen and rendered diagrams get a Full screen link above them: a same-origin path the app
 * intercepts (/full-screen) to show that figure alone with pinch zoom and rotation, through figure() below.
 */
function addFigureTools() {
  const tableTools = [];
  const tools = (element, id) => {
    element.dataset.figure = id;
    const row = document.createElement('div');
    row.className = 'figure-tools';
    const link = document.createElement('a');
    link.href = `/full-screen?figure=${encodeURIComponent(id)}`;
    link.textContent = 'Full screen';
    row.append(link);
    element.before(row);
    return row;
  };
  document.querySelectorAll('#note table').forEach((table, index) => tableTools.push([table, tools(table, `table-${index}`)]));
  for (const diagram of document.querySelectorAll('#note .diagram')) {
    tools(diagram, `diagram-${diagram.nextElementSibling.querySelector('pre[data-mermaid]').dataset.blockId}`);
  }
  const showTableTools = () => {
    for (const [table, row] of tableTools) row.hidden = table.scrollWidth <= table.clientWidth;
  };
  showTableTools();
  window.addEventListener('resize', showTableTools);
}

/*
 * Repository images come from the app's cache or the authenticated backend; when neither has one, say so in place. The
 * label is drawn by CSS from an attribute, so it adds no text to the block: canonical text must not change.
 */
for (const image of document.querySelectorAll('#note img')) {
  const unavailable = () => {
    const label = document.createElement('span');
    label.className = 'image-blocked';
    label.dataset.label = `[Image unavailable: ${image.alt}]`;
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

/*
 * Where a block appears on screen. A Mermaid source sits collapsed under its rendered diagram, and scrolling to
 * collapsed content does nothing, so the diagram stands in for it. The anchor still names the canonical source block.
 */
function visibleElement(block) {
  let visible = block;
  if (document.body.classList.contains('study-active')) {
    for (let section = block.closest('.study-section'); section; section = section.parentElement.closest('.study-section')) {
      if (!section.classList.contains('study-revealed') && section.querySelector(':scope > .study-body').contains(block)) {
        visible = section.querySelector('h1,h2,h3,h4,h5,h6');
      }
    }
  }
  if (visible !== block) return visible;
  const details = block.closest('details');
  if (details && !details.open && details.previousElementSibling?.matches('.diagram, .diagram-error')) {
    return details.previousElementSibling;
  }
  return block;
}

/** Scrolls explicitly; Element.scrollIntoView silently does nothing for collapsed content in this WebView. */
function scrollToElement(element, align) {
  const rect = element.getBoundingClientRect();
  const offset = align === 'center' ? (window.innerHeight - rect.height) / 2 : 0;
  window.scrollTo(0, Math.max(0, window.scrollY + rect.top - offset));
}

/** True when the element ended up within the viewport, so a restore can only claim the passage it actually shows. */
function onScreen(element) {
  const rect = element.getBoundingClientRect();
  return rect.bottom > 0 && rect.top < window.innerHeight;
}

function scrollPercent(y = window.scrollY) {
  const scrollable = document.documentElement.scrollHeight - window.innerHeight;
  return scrollable <= 0 ? 100 : Math.max(0, Math.min(100, Math.round(y / scrollable * 100)));
}

/*
 * Reading position for the native app, which calls these through evaluateJavascript; the page has no way to call native
 * code. The anchor is the first block still visible at the top of the viewport.
 */
window.reporead = {
  position() {
    // Scrolling lands on device pixels, so a sliver under 1px of the previous block does not count as visible.
    const question = document.body.classList.contains('study-recall') ? studyQuestions[studyIndex] : null;
    const index = question ? blocks.findIndex(block => block.dataset.blockId === question.blockId)
      : blocks.findIndex(block => visibleElement(block).getBoundingClientRect().bottom > 1);
    const block = index < 0 ? null : blocks[index];
    let progressPercent;
    if (question) {
      // Measure its position in the note, not in the short recall panel (which would falsely mark it 100% read).
      document.body.classList.remove('study-recall');
      progressPercent = scrollPercent(window.scrollY + visibleElement(block).getBoundingClientRect().top);
      document.body.classList.add('study-recall');
    } else progressPercent = scrollPercent();
    return {
      progressPercent,
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
    if (target) {
      if (document.body.classList.contains('study-recall')) {
        const index = studyQuestions.findIndex(question => question.blockId === target.dataset.blockId);
        if (index >= 0) {
          studyIndex = index;
          updateStudyQuestion();
          window.scrollTo(0, 0);
          return mode;
        }
        document.body.classList.remove('study-recall');
        updateStudyQuestion();
      }
      scrollToElement(visibleElement(target), 'start');
      if (onScreen(visibleElement(target))) return visibleElement(target).matches('h1,h2,h3,h4,h5,h6') && visibleElement(target) !== target ? 'collapsed' : mode;
    }
    window.scrollTo(0, progressPercent / 100 * (document.documentElement.scrollHeight - window.innerHeight));
    return 'percent';
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

/**
 * Scrolls to the heading [text] names: the first whose text it is (ignoring case and spacing), as Obsidian heading
 * links do, or else the one whose GitHub anchor it is, as Markdown links `note.md#some-heading` do. GitHub's anchor:
 * lowercase, punctuation removed, each space a hyphen, and repeats numbered -1, -2… in page order.
 */
window.reporead.showHeading = text => {
  const wanted = text.trim().toLowerCase();
  const headings = blocks.filter(block => /^H[1-6]$/.test(block.tagName));
  const seen = new Map();
  const anchor = block => {
    const slug = block.dataset.anchorText.trim().toLowerCase().replace(/[^\p{L}\p{M}\p{N}\p{Pc} -]/gu, '').replace(/ /g, '-');
    const repeats = seen.get(slug) ?? 0;
    seen.set(slug, repeats + 1);
    return repeats === 0 ? slug : `${slug}-${repeats}`;
  };
  const heading = headings.find(block => block.dataset.anchorText.trim().toLowerCase() === wanted)
    ?? headings.find(block => anchor(block) === wanted);
  if (!heading) return false;
  revealStudyBlock(heading);
  scrollToElement(heading, 'start');
  return true;
};

/**
 * The note's headings in order, for the outline: block id, index in the block list (comparable with position()'s
 * blockIndex), level, and the text as shown — hidden link targets are left out, so it reads like the page.
 */
window.reporead.outline = () => blocks.flatMap((block, index) => /^H[1-6]$/.test(block.tagName)
  ? [{blockId: block.dataset.blockId, index, level: Number(block.tagName[1]), text: block.innerText.trim()}] : []);

/** A code block's text for copying: its canonical text without the final line break, so pasting a command does not run it. */
window.reporead.codeText = blockId => {
  const pre = blocks.find(block => block.dataset.blockId === blockId && block.tagName === 'PRE');
  if (!pre) throw new Error(`No code block ${blockId}`);
  return pre.dataset.anchorText.replace(/\n$/, '');
};

/**
 * For the full-screen view, a separate page: keeps only the table or diagram [id] (from addFigureTools) and the footnote
 * sheet. Returns whether it exists in this version.
 */
window.reporead.figure = id => {
  const figure = note.querySelector(`[data-figure="${CSS.escape(id)}"]`);
  if (!figure) return false;
  document.body.replaceChildren(figure, footnoteSheet);
  document.body.classList.add('figure');
  return true;
};

/** Scrolls a changed section's heading block to the top of the screen; null is the beginning of the note. */
window.reporead.showBlock = blockId => {
  if (blockId === null) {
    revealStudyBlock(note);
    window.scrollTo(0, 0);
    return true;
  }
  const block = blocks.find(candidate => candidate.dataset.blockId === blockId);
  if (!block) return false;
  revealStudyBlock(block);
  scrollToElement(visibleElement(block), 'start');
  return true;
};

window.reporead.reveal = key => {
  const mark = document.querySelector(`#note mark[data-key="${CSS.escape(key)}"]`);
  if (!mark) return false;
  revealStudyBlock(mark);
  const details = mark.closest('details');
  if (details) details.open = true;
  scrollToElement(mark, 'center');
  mark.classList.add('revealed');
  setTimeout(() => mark.classList.remove('revealed'), 1500);
  return true;
};

/*
 * Tapping a footnote number shows its definition in a sheet at the bottom, without scrolling away from the passage.
 * The sheet is outside #note, so nothing in it is a block: it cannot be highlighted or become the reading position.
 */
const note = document.getElementById('note');
const footnoteSheet = document.createElement('aside');
footnoteSheet.id = 'footnote';
footnoteSheet.hidden = true;
document.body.append(footnoteSheet);

function showFootnote(label) {
  // Looked up in the note, which figure() takes out of the page: a table's footnotes still open there.
  const marker = note.querySelector(`.fn-def[data-footnote="${CSS.escape(label)}"]`);
  if (!marker) throw new Error(`Footnote ${label} is referenced but has no definition marker`);
  const close = document.createElement('button');
  close.textContent = '×';
  close.setAttribute('aria-label', 'Close footnote');
  close.addEventListener('click', () => { footnoteSheet.hidden = true; });
  const number = document.createElement('span');
  number.className = 'footnote-number';
  number.textContent = `${marker.dataset.label}.`;
  footnoteSheet.replaceChildren(close, number);
  // A definition runs to the next definition marker or the end of its paragraph.
  for (let node = marker.nextSibling; node && !node.matches?.('.fn-def'); node = node.nextSibling) footnoteSheet.append(node.cloneNode(true));
  footnoteSheet.hidden = false;
}

// A listener on each reference also makes the browser's touch adjustment treat these small numbers as tap targets.
for (const reference of note.querySelectorAll('.fn-ref')) {
  reference.addEventListener('click', event => {
    event.stopPropagation();
    showFootnote(reference.dataset.footnote);
  });
}
document.addEventListener('click', event => {
  if (!footnoteSheet.contains(event.target)) footnoteSheet.hidden = true;
});

/* P3 recognition belongs to Study.kt. The page only lays out that model, without changing any block's text. */
let studyQuestions = [];
let studyIndex = 0;
let studyPanel = null;

// P4 reads the displayed question's identity, including after its answer was revealed.
window.reporead.currentStudyQuestion = () => studyQuestions[studyIndex]?.blockId ?? null;

function revealStudyBlock(block) {
  document.body.classList.remove('study-recall');
  for (let section = block.closest('.study-section'); section; section = section.parentElement.closest('.study-section')) {
    section.classList.add('study-revealed');
    section.querySelector(':scope > button').setAttribute('aria-expanded', 'true');
  }
  updateStudyQuestion();
}

function updateStudyQuestion() {
  if (!studyPanel) return;
  const active = document.body.classList.contains('study-active');
  studyPanel.hidden = !active;
  const question = studyQuestions[studyIndex];
  studyPanel.querySelector('.study-count').textContent = `Question ${studyIndex + 1} of ${studyQuestions.length}`;
  studyPanel.querySelector('.study-question').textContent = question.text;
  studyPanel.querySelector('.study-previous').disabled = studyIndex === 0;
  studyPanel.querySelector('.study-next').disabled = studyIndex === studyQuestions.length - 1;
  studyPanel.querySelector('.study-answer').textContent = document.body.classList.contains('study-recall') ? 'Read the answer' : 'Recall this question';
}

window.reporead.configureStudy = model => {
  if (document.querySelector('.study-section') || studyPanel) throw new Error('Study model configured twice');
  for (const id of model.collapsedHeadings) {
    const heading = blocks.find(block => block.dataset.blockId === id);
    if (!heading || !/^H[1-6]$/.test(heading.tagName)) throw new Error(`Study heading ${id} missing`);
    const section = document.createElement('section');
    section.className = 'study-section';
    const body = document.createElement('div');
    body.className = 'study-body';
    const button = document.createElement('button');
    button.textContent = 'Show / hide section';
    button.setAttribute('aria-label', `Show or hide ${heading.innerText.trim()}`);
    button.setAttribute('aria-expanded', 'false');
    button.addEventListener('click', () => {
      button.setAttribute('aria-expanded', String(section.classList.toggle('study-revealed')));
    });
    heading.before(section);
    let next = heading.nextSibling;
    section.append(heading, button, body);
    while (next && !(/^H[1-6]$/.test(next.nodeName) && Number(next.nodeName[1]) <= Number(heading.tagName[1]))) {
      const following = next.nextSibling;
      body.append(next);
      next = following;
    }
  }
  studyQuestions = model.questions;
  if (studyQuestions.length) {
    studyPanel = document.createElement('aside');
    studyPanel.id = 'study-questions';
    studyPanel.hidden = true;
    studyPanel.innerHTML = '<div class="study-count"></div><p class="study-question"></p><div class="study-actions">' +
      '<button class="study-previous">Previous question</button><button class="study-next">Next question</button>' +
      '<button class="study-answer">Read the answer</button></div>';
    const move = offset => {
      studyIndex += offset;
      document.body.classList.add('study-recall');
      updateStudyQuestion();
      window.scrollTo(0, 0);
    };
    studyPanel.querySelector('.study-previous').addEventListener('click', () => move(-1));
    studyPanel.querySelector('.study-next').addEventListener('click', () => move(1));
    studyPanel.querySelector('.study-answer').addEventListener('click', () => {
      const recall = document.body.classList.toggle('study-recall');
      updateStudyQuestion();
      if (recall) window.scrollTo(0, 0);
      else window.reporead.showBlock(studyQuestions[studyIndex].blockId);
    });
    note.before(studyPanel);
  }
  for (const block of blocks) {
    if (block.textContent !== block.dataset.anchorText) throw new Error(`Study changed canonical text in ${block.dataset.blockId}`);
  }
};

window.reporead.setStudy = enabled => {
  const saved = window.reporead.position();
  document.body.classList.toggle('study-active', enabled);
  document.body.classList.toggle('study-recall', enabled && studyQuestions.length > 0);
  updateStudyQuestion();
  if (enabled && studyQuestions.length) window.scrollTo(0, 0);
  else window.reporead.restore(saved.anchor, saved.progressPercent);
};

/** Block identity is valid only in its source version; repeated prompt text is not an identity. */
window.reporead.studyQuestion = (blobSha, blockId) => {
  if (blobSha !== document.body.dataset.sourceBlobSha) return false;
  const index = studyQuestions.findIndex(question => question.blockId === blockId);
  if (index < 0) return false;
  studyIndex = index;
  document.body.classList.add('study-active', 'study-recall');
  updateStudyQuestion();
  window.scrollTo(0, 0);
  return true;
};

// Diagrams change the layout, so the app restores a position only once rendering has finished.
render().then(() => {
  document.body.dataset.state = 'ready';
}, error => {
  document.body.dataset.state = 'failed';
  const failure = document.getElementById('render-status');
  failure.textContent = `Reader failed: ${error.message}`;
  failure.className = 'failed';
  console.error(error);
});
