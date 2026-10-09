// Device check: restores a saved position for every block of the note open in the debug reader and reads it back.
// Layout-dependent behaviour (collapsed diagram sources, table rows, device-pixel rounding) only exists on a real
// WebView, so this runs through Chrome DevTools. Usage is in README.md. Exits 1 if any block restores wrongly.
const [webSocketUrl] = process.argv.slice(2);
if (!webSocketUrl) throw new Error('check-restore.mjs requires the reader page webSocketDebuggerUrl');

const expression = `(() => {
  if (document.body.classList.contains('study-recall')) throw new Error('Read the answer first: this check needs the note visible, not the recall panel.');
  // The app saves the reading position after scrolling, so the check must leave the page where it found it.
  const original = scrollY;
  const blocks = [...document.querySelectorAll('#note [data-block-id]')];
  const headings = b => { const p = []; for (let l = 1; l <= 6; l++) { const h = b.getAttribute('data-heading-' + l); if (h !== null) p.push(h); } return p; };
  const shown = b => {
    let visible = b;
    if (document.body.classList.contains('study-active')) {
      for (let s = b.closest('.study-section'); s; s = s.parentElement.closest('.study-section')) {
        if (!s.classList.contains('study-revealed') && s.querySelector(':scope > .study-body').contains(b)) visible = s.querySelector('h1,h2,h3,h4,h5,h6');
      }
    }
    if (visible !== b) return visible;
    const d = b.closest('details'); return d && !d.open ? d.previousElementSibling : b;
  };
  const maxScroll = document.documentElement.scrollHeight - innerHeight;
  const result = {blocks: blocks.length, same: 0, sameRow: 0, collapsed: 0, endOfNote: 0, wrong: [], approximate: []};
  for (const [i, block] of blocks.entries()) {
    scrollTo(0, 0);
    const mode = window.reporead.restore({headingPath: headings(block), textPrefix: block.dataset.anchorText.slice(0, 64), blockIndex: i}, 0);
    // The last screen of a note cannot be scrolled to the top, so its blocks read back as an earlier one.
    if (scrollY >= maxScroll - 1) { result.endOfNote++; continue; }
    const got = window.reporead.position().anchor.blockIndex;
    if (mode === 'collapsed' && blocks[got] === shown(block)) result.collapsed++;
    else if (mode !== 'exact' && mode !== 'text') result.approximate.push(i + ':' + mode);
    else if (got === i || blocks[got].dataset.anchorText.slice(0, 64) === block.dataset.anchorText.slice(0, 64)) result.same++;
    else if (Math.abs(shown(blocks[got]).getBoundingClientRect().top - shown(block).getBoundingClientRect().top) < 1) result.sameRow++;
    else result.wrong.push(i + '->' + got + ':' + block.tagName);
  }
  scrollTo(0, original);
  return result;
})()`;

const socket = new WebSocket(webSocketUrl);
socket.onopen = () => socket.send(JSON.stringify({id: 1, method: 'Runtime.evaluate', params: {expression, returnByValue: true}}));
socket.onmessage = event => {
  const message = JSON.parse(event.data);
  if (message.id !== 1) return;
  socket.close();
  const result = message.result?.result?.value;
  if (!result) throw new Error(`Reader page did not return a result: ${JSON.stringify(message)}`);
  console.log(JSON.stringify(result));
  process.exitCode = result.wrong.length || result.approximate.length ? 1 : 0;
};
