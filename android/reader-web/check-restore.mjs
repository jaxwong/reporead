// Device check: for every block of the note open in the debug reader, saves the reading position with that block at the
// top, leaves it, restores it, and reads it back — all through the reader's own window.reporead, never a copy of it.
// Layout-dependent behaviour (collapsed diagram sources, table rows, device-pixel rounding) only exists on a real
// WebView, so this runs through Chrome DevTools. Usage is in README.md. Exits 1 if any block restores wrongly.
const [webSocketUrl] = process.argv.slice(2);
if (!webSocketUrl) throw new Error('check-restore.mjs requires the reader page webSocketDebuggerUrl');

const expression = `(() => {
  if (document.body.classList.contains('study-recall')) throw new Error('Read the answer first: this check needs the note visible, not the recall panel.');
  // The app saves the reading position after scrolling, so the check must leave the page where it found it.
  const original = scrollY;
  const {reporead} = window;
  const blocks = [...document.querySelectorAll('#note [data-block-id]')];
  const maxScroll = document.documentElement.scrollHeight - innerHeight;
  // An anchor naming no section and no text: restore falls through to its block index and scrolls that block, or what
  // stands in for it on screen, to the top.
  const place = index => reporead.restore({headingPath: null, textPrefix: null, blockIndex: index}, 0);
  const result = {blocks: blocks.length, exact: 0, text: 0, sameRow: 0, collapsed: 0, endOfNote: 0, wrong: [], approximate: []};
  for (const [i, block] of blocks.entries()) {
    const placed = place(i);
    if (placed !== 'block' && placed !== 'collapsed') { result.approximate.push(i + ':' + placed); continue; }
    // The last screen of a note cannot be scrolled to the top, so its blocks read back as an earlier one.
    if (scrollY >= maxScroll - 1) { result.endOfNote++; continue; }
    const top = scrollY;
    const saved = reporead.position();
    const got = saved.anchor.blockIndex;
    if (got !== i) {
      // Another block may be saved only when it starts on the same line: the heading of a hidden study section, or the
      // first cell of a table row.
      place(got);
      if (Math.abs(scrollY - top) >= 1) result.wrong.push(i + '->' + got + ':' + block.tagName);
      else result[placed === 'collapsed' ? 'collapsed' : 'sameRow']++;
      continue;
    }
    // The app's round trip: leave the saved position, then reopen the note at it.
    scrollTo(0, top < maxScroll / 2 ? maxScroll : 0);
    const mode = reporead.restore(saved.anchor, saved.progressPercent);
    const restored = reporead.position().anchor;
    if (mode !== 'exact' && mode !== 'text') result.approximate.push(i + ':' + mode);
    else if (JSON.stringify(restored) === JSON.stringify(saved.anchor)) result[mode]++;
    else result.wrong.push(i + '->' + restored.blockIndex + ':' + block.tagName);
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
