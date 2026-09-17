import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const readerTextSemanticsUrl = new URL('../../main/assets/hoshi-web/reader/reader-text-semantics.js', import.meta.url);

function loadTextSemantics() {
    const source = fs.readFileSync(readerTextSemanticsUrl, 'utf8');
    const window = {};
    vm.runInNewContext(source, { window });
    return window.hoshiReaderTextSemantics;
}

test('Korean ranges match native normalization without changing raw counts or other scripts', () => {
    const semantics = loadTextSemantics();
    // Same input and expectation as ReaderTextFilterTest.
    const text = '가힣ㄱㆎ 한글 日本語 Aｚ9、! 𠮟🙂\uABFF\uD7A4\u3130\u318F\u1100\u1161';
    assert.equal(semantics.normalizeText(text), '가힣ㄱㆎ한글日本語Aｚ9𠮟');
    assert.equal(semantics.countChars(text), 13);
    assert.equal(semantics.countRawChars(text), 26);
    for (const char of '가힣ㄱㆎ') assert.equal(semantics.isMatchableChar(char), true);
    for (const char of '\uABFF\uD7A4\u3130\u318F\u1100\u1161') {
        assert.equal(semantics.isMatchableChar(char), false);
    }
});

test('reader text semantics normalizes matchable text while preserving raw counts', () => {
    const semantics = loadTextSemantics();

    assert.equal(semantics.normalizeText('一、二。A!'), '一二A');
    assert.equal(semantics.countChars('一、二。A!'), 3);
    assert.equal(semantics.countRawChars('一、二。A!'), 6);
    assert.equal(semantics.isMatchableChar('一'), true);
    assert.equal(semantics.isMatchableChar('、'), false);
});

test('reader text semantics classifies Japanese characters used for ruby-adjacent wrapping', () => {
    const semantics = loadTextSemantics();

    assert.equal(semantics.isJapaneseBreakCharacter('貴'), true);
    assert.equal(semantics.isJapaneseBreakCharacter('、'), true);
    assert.equal(semantics.isJapaneseBreakCharacter('A'), false);
    assert.equal(semantics.isJapaneseBreakCharacter(' '), false);
});
