import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../../main/assets/hoshi-web/reader/reader-continuous-group.js', import.meta.url), 'utf8');
const semantics = fs.readFileSync(new URL('../../main/assets/hoshi-web/reader/reader-text-semantics.js', import.meta.url), 'utf8');
const plain = value => JSON.parse(JSON.stringify(value));

function fixture({ vertical = false, texts = ['Alpha', '', 'Beta'], extra = {} } = {}) {
    const elements = texts.map((text, index) => ({
        nodes: text ? [{ textContent: text }] : [],
        getBoundingClientRect() { return this.rect; },
        rect: { top: index * 1000, bottom: (index + 1) * 1000, height: 1000, right: 480 - index * 1000, left: -520 - index * 1000, width: 1000 },
        contains(element) { return element.sourceIndex === index; },
    }));
    const children = [];
    const document = {
        body: { appendChild: child => children.push(child) },
        querySelector(selector) {
            const match = selector.match(/data-hoshi-source-index="(\d+)"/);
            return match ? elements[Number(match[1])] : null;
        },
        querySelectorAll: () => [],
        createElement() {
            return { attributes: {}, setAttribute(key, value) { this.attributes[key] = value; }, style: { setProperty(key, value, priority) { this[key] = { value, priority }; } } };
        },
    };
    const window = { innerWidth: 480, getComputedStyle: () => ({ fontSize: '24px' }), ...extra };
    vm.runInNewContext(semantics + '\n' + source, { window, document });
    const reader = {
        isVertical: () => vertical,
        countChars: window.hoshiReaderTextSemantics.countChars,
        countRawChars: window.hoshiReaderTextSemantics.countRawChars,
        countCharsBeforeViewport: node => node.before ?? 0,
        createWalker(element) { let index = 0; return { nextNode: () => element.nodes[index++] ?? null }; },
        buildNodeOffsets() {},
    };
    window.hoshiContinuousGroup.install(reader, texts.map((text, index) => ({ index, count: reader.countChars(text), hasText: text.trim().length > 0 })));
    return { group: window.hoshiContinuousGroup, reader, window, elements, children };
}

for (const vertical of [false, true]) {
    test(`Continuous adds one reader-font 15em buffer to mixed groups (${vertical ? 'vertical' : 'horizontal'})`, () => {
        const mixed = fixture({ vertical, texts: ['Alpha', '', ''] });
        assert.equal(mixed.children.length, 1);
        const buffer = mixed.children[0];
        assert.equal(buffer.attributes['data-hoshi-continuous-buffer'], '');
        assert.match(buffer.style.cssText, /block-size:15em!important/);
        assert.match(buffer.style.cssText, /writing-mode:inherit!important/);
        assert.deepEqual(buffer.style['font-size'], { value: '24px', priority: 'important' });
        mixed.group.offsets();
        assert.equal(mixed.group.sources.at(-1).normalizedEnd, 5);
        assert.equal(fixture({ vertical, texts: ['', ''] }).children.length, 0);
    });

    test(`Continuous reports a zero-text image source and text completion without counting the buffer (${vertical ? 'vertical' : 'horizontal'})`, () => {
        const { group, elements } = fixture({ vertical });
        elements.forEach((element, index) => {
            element.rect.top = (index - 1) * 1000 - 250;
            element.rect.bottom = element.rect.top + 1000;
            element.rect.right = 480 - (index - 1) * 1000 + 250;
            element.rect.left = element.rect.right - 1000;
        });
        assert.deepEqual(plain(group.sourcePosition()), { index: 1, progress: 0.25 });
        elements[2].rect = { top: -1200, bottom: -200, height: 1000, right: 1500, left: 500, width: 1000 };
        assert.deepEqual(plain(group.sourcePosition()), { index: 2, progress: 1 });
    });
}

test('Continuous round-trips each original source including zero-text image positions and exact boundaries', () => {
    const { group, window } = fixture();
    for (let index = 0; index < 3; index++) {
        window.__hoshiContinuousInitialSourceIndex = index;
        for (const progress of [0, 0.25, 0.75, 1]) {
            const restored = group.restoreSource(group.renderedProgress({ index, progress }));
            assert.equal(restored.source.index, index);
            assert.ok(Math.abs(restored.progress - progress) < 1e-9);
        }
    }
});

test('Continuous selections, search, saved highlights and Sasayaki cues use source-local offsets', () => {
    const posted = [], applied = [], searches = [], cues = [];
    const { group, reader, window } = fixture({ texts: ['A。猫', '', 'B犬'], extra: {
        hoshiSelection: { postTextSelectedAfterPaint(payload) { posted.push(payload); } },
        hoshiHighlights: { applyHighlights(items) { applied.push(...items); }, showSearchHighlight(offset, length) { searches.push([offset, length]); } },
    } });
    window.hoshiSelection.postTextSelectedAfterPaint({ normalizedOffset: 3, sentence: 'B犬' });
    assert.deepEqual(plain(posted), [{ normalizedOffset: 1, sentence: 'B犬', sourceIndex: 2 }]);
    window.hoshiHighlights.applyHighlights([{ sourceIndex: 2, offset: 1, text: '犬' }]);
    assert.equal(applied[0].offset, 4);
    window.hoshiHighlights.showSourceSearchHighlight(2, 1, 1);
    assert.deepEqual(searches, [[3, 1]]);
    reader.applySasayakiCues = items => cues.push(...items);
    assert.deepEqual(plain(group.renderedCue({ sourceIndex: 2, start: 1, id: 'cue', length: 1 })), { start: 3, id: 'cue', length: 1 });
    assert.equal(group.renderedCue(group.renderedCue({ sourceIndex: 2, start: 1 })).start, 3);
});

test('Continuous splits a cross-file highlight into independently editable original-file highlights', () => {
    const wrapped = [], removed = [];
    const text = 'A。猫B犬';
    const { window } = fixture({ texts: ['A。猫', '', 'B犬'], extra: { hoshiHighlights: {
        applyHighlights() {}, showSearchHighlight() {},
        createHighlight() { return { action: 'created', offset: 1, text: '。猫B', start: 1 }; },
        textForRange(offset, length) { return { text: text.slice(offset, offset + length), textFurigana: null }; },
        removeHighlight(id) { removed.push(id); }, wrapHighlight(item) { wrapped.push(item); },
    } } });
    const result = window.hoshiHighlights.createHighlight('Yellow', 'highlight');
    assert.deepEqual(plain(result.parts), [
        { id: 'highlight', sourceIndex: 0, start: 1, offset: 1, text: '。猫', textFurigana: null },
        { id: 'highlight-2', sourceIndex: 2, start: 0, offset: 0, text: 'B', textFurigana: null },
    ]);
    assert.deepEqual(removed, ['highlight']);
    assert.deepEqual(wrapped.map(item => [item.id, item.offset, item.text]), [['highlight', 1, '。猫'], ['highlight-2', 3, 'B']]);
});

test('Continuous chapter-end Sasayaki image stops stay within the visible original source', () => {
    const { group, reader, elements } = fixture();
    const media = [{ sourceIndex: 0, scroll: 10 }, { sourceIndex: 0, scroll: 20 }, { sourceIndex: 1, scroll: 1000 }];
    reader.sasayakiMediaStopsToChapterEnd = () => ['fallback'];
    reader.sasayakiScrollPosition = () => 0;
    reader.sasayakiMediaElements = () => media;
    reader.sasayakiMediaScrollForElement = element => element.scroll;
    // Install once after the optional Sasayaki API becomes available.
    const { group: scoped, reader: scopedReader } = fixture();
    Object.assign(scopedReader, reader);
    scopedReader.sasayakiMediaStopsToChapterEnd = () => ['fallback'];
    scoped.install(scopedReader, group.sources.map(({ index, count, hasText }) => ({ index, count, hasText })));
    assert.deepEqual(plain(scopedReader.sasayakiMediaStopsToChapterEnd()), [{ scroll: 10 }, { scroll: 20 }]);
});
