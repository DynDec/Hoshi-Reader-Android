import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../../main/assets/hoshi-web/reader/reader-selection-preview.js', import.meta.url), 'utf8');

function loadPreview() {
    const selection = {
        options: {},
        configure(options) { Object.assign(this.options, options); },
        selectionRects(count) {
            assert.equal(count, 1);
            return [{ x: 10, y: 20, width: 14, height: 22 }];
        },
    };
    const window = { hoshiSelection: selection };
    vm.runInNewContext(source, { window });
    return { window, selection, preview: window.hoshiReaderSelectionPreview };
}

test('reader preview queues geometry until the popup host loads and can cancel it', () => {
    const { window, selection, preview } = loadPreview();
    preview.configure({ darkMode: false, eInkMode: true, verticalWriting: true });
    selection.options.previewSelection(selection);
    const pending = JSON.parse(JSON.stringify(window.__hoshiPendingReaderSelectionPreview));
    assert.deepEqual(pending, {
        rects: [{ x: 10, y: 20, width: 14, height: 22 }],
        darkMode: false, eInkMode: true, verticalWriting: true,
    });
    selection.options.clearSelectionPreview();
    assert.equal(window.__hoshiPendingReaderSelectionPreview, null);
});

test('reader preview sends live geometry with updated appearance and clears the host', () => {
    const { window, selection, preview } = loadPreview();
    const received = [];
    let cleared = 0;
    window.hoshiReaderPopupHost = {
        previewRootSelection(payload) { received.push(payload); },
        clearRootSelectionPreview() { cleared += 1; },
    };
    preview.configure({ darkMode: false, eInkMode: true, verticalWriting: true });
    selection.options.previewSelection(selection);
    preview.configure({ darkMode: true, eInkMode: false, verticalWriting: false });
    selection.options.previewSelection(selection);
    assert.equal(received.length, 2);
    assert.equal(received[0].darkMode, false);
    assert.equal(received[1].darkMode, true);
    assert.equal(received[1].eInkMode, false);
    assert.equal(received[1].verticalWriting, false);
    assert.equal(window.__hoshiPendingReaderSelectionPreview, undefined);
    selection.options.clearSelectionPreview();
    assert.equal(cleared, 1);
    assert.equal(window.__hoshiPendingReaderSelectionPreview, null);
});
