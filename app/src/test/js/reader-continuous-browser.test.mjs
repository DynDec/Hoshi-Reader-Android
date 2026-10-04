import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import test from 'node:test';

// Optional real CSSOM/layout coverage. Set HOSHI_TEST_CHROMIUM on other hosts.
const chromium = [process.env.HOSHI_TEST_CHROMIUM,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find(candidate => candidate && fs.existsSync(candidate));
const asset = name => fs.readFileSync(new URL(`../../main/assets/hoshi-web/reader/${name}`, import.meta.url), 'utf8');

test('Continuous renders one buffer in both axes and preserves source CSS, cross-file highlights and restoration', { skip: !chromium }, () => {
    let reader = asset('reader-continuous.js');
    for (const [token, filename] of Object.entries({
        CONTINUOUS_GROUP: 'reader-continuous-group.js', HIGHLIGHTS: 'highlights.js',
        READER_VIEWPORT: 'reader-viewport.js', READER_SASAYAKI: 'reader-sasayaki.js',
        READER_TEXT_SEMANTICS: 'reader-text-semantics.js', READER_DOM_TEXT: 'reader-dom-text.js',
        READER_MEDIA_SEMANTICS: 'reader-media-semantics.js', READER_LAYOUT_SEMANTICS: 'reader-layout-semantics.js',
    })) reader = reader.replace(`__HOSHI_${token}_SCRIPT__`, asset(filename));
    reader = reader.replaceAll('__HOSHI_RESTORE_TOKEN_LITERAL__', '"fixture"')
        .replaceAll('__HOSHI_VERTICAL_PADDING_BLOCK_RATIO__', '0')
        .replaceAll('__HOSHI_VERTICAL_PADDING_GAP_RATIO__', '0')
        .replaceAll('__HOSHI_IMAGE_WIDTH_VIEWPORT_RATIO__', '1')
        .replaceAll('__HOSHI_IMAGE_HEIGHT_VIEWPORT_RATIO__', '1')
        .replaceAll('__HOSHI_IMAGE_WIDTH_REDUCTION_PX__', '0')
        .replaceAll('__HOSHI_BLUR_IMAGES__', 'false')
        .replaceAll('__HOSHI_TRAILING_SPACER_HEIGHT_LITERAL__', '"0px"')
        .replaceAll('__HOSHI_TRAILING_SPACER_WIDTH_LITERAL__', '"0px"')
        .replaceAll('__HOSHI_RESTORE_SCRIPTS__', 'window.fixtureReady = true;');
    const image = '<img width="120" height="120" src="data:image/svg+xml,%3Csvg xmlns=\'http://www.w3.org/2000/svg\' width=\'120\' height=\'120\'%3E%3Crect width=\'120\' height=\'120\' fill=\'gray\'/%3E%3C/svg%3E">';
    const documents = [];
    for (const vertical of [false, true]) for (const imageOnly of [false, true]) {
        const scope = index => `hoshi-source-${index}`;
        const body = [0, 1, 2].map(index => `<section id="${scope(index)}" data-hoshi-source-index="${index}"><div data-hoshi-source-body id="${scope(index)}-body">${index === 1 || imageOnly ? image : `<p id="${scope(index)}-same">${index === 0 ? 'Alpha猫' : 'Beta犬'}</p>`}</div></section>`).join('');
        const config = [0, 1, 2].map(index => ({ index, count: imageOnly || index === 1 ? 0 : index === 0 ? 6 : 5, hasText: !imageOnly && index !== 1 }));
        const style = (index, color) => `<style media="not all" data-hoshi-source-style="${scope(index)}">@font-face{font-family:Publisher;src:local(Arial)}@font-face{font-family:Publisher;src:local(Arial);font-weight:700}@font-face{font-family:Alternative;src:local(Arial)}@keyframes glow{from{opacity:.9}to{opacity:1}}html body #same{color:${color};font-family:Publisher,Alternative;animation:glow 1s}body{margin:0}</style>`;
        const doc = `<html><head><style>html,body{margin:0;padding:0;writing-mode:${vertical ? 'vertical-rl' : 'horizontal-tb'}}body{font-size:24px;${vertical ? 'height:300px' : 'width:300px'}}p{margin:0}section{display:block}</style>${style(0, 'red')}${style(2, 'blue')}</head><body>${body}<script>window.__hoshiContinuousSources=${JSON.stringify(config)};window.__hoshiContinuousInitialSourceIndex=0;<\/script><script>${reader.replaceAll('</script', '<\\/script')}<\/script></body></html>`;
        documents.push({ vertical, imageOnly, html: doc });
    }
    for (const vertical of [false, true]) for (const imageOnly of [false, true]) {
        const config = [{ index: 4, count: imageOnly ? 0 : 6, hasText: !imageOnly }];
        const doc = `<html><head><style>html,body{margin:0;padding:0;writing-mode:${vertical ? 'vertical-rl' : 'horizontal-tb'}}body{font-size:24px;${vertical ? 'height:300px' : 'width:300px'}}</style></head><body>${imageOnly ? image : '<p>Alpha猫</p>'}<script>window.__hoshiContinuousSources=${JSON.stringify(config)};window.__hoshiContinuousInitialSourceIndex=4;<\/script><script>${reader.replaceAll('</script', '<\\/script')}<\/script></body></html>`;
        documents.push({ vertical, imageOnly, singleton: true, html: doc });
    }
    const html = `<html><body><pre id="results">pending</pre><script>
      const documents = ${JSON.stringify(documents).replaceAll('</script', '<\\/script')};
      const outputs = [];
      Promise.all(documents.map(async fixture => {
        const frame = document.createElement('iframe'); frame.width=300;frame.height=300;document.body.appendChild(frame);
        frame.srcdoc = fixture.html;
        await new Promise(resolve => frame.onload=resolve);
        const w=frame.contentWindow, d=w.document;
        for(let i=0;i<100 && !w.fixtureReady;i++) await new Promise(resolve=>setTimeout(resolve,20));
        if(!w.fixtureReady) throw Error('Reader initialization failed');
        const buffers=d.querySelectorAll('[data-hoshi-continuous-buffer]'), buffer=buffers[0];
        const output={vertical:fixture.vertical,imageOnly:fixture.imageOnly,singleton:fixture.singleton,buffers:buffers.length};
        if(buffer){const r=buffer.getBoundingClientRect();output.bufferLength=fixture.vertical?r.width:r.height;output.beforeEnd=w.hoshiReader.calculateProgress();buffer.scrollIntoView({block:'end',inline:'end'});output.afterEnd=w.hoshiReader.calculateProgress();}
        if(!fixture.imageOnly && !fixture.singleton){
          const first=d.getElementById('hoshi-source-0-same'),last=d.getElementById('hoshi-source-2-same');
          output.colors=[w.getComputedStyle(first).color,w.getComputedStyle(last).color];
          output.fonts=[w.getComputedStyle(first).fontFamily,w.getComputedStyle(last).fontFamily];
          output.animations=[w.getComputedStyle(first).animationName,w.getComputedStyle(last).animationName];
          const range=d.createRange();range.setStart(first.firstChild,1);range.setEnd(last.firstChild,2);
          w.getSelection().removeAllRanges();w.getSelection().addRange(range);
          const highlight=w.hoshiHighlights.createHighlight('yellow','cross-file');
          output.highlightParts=highlight.parts.map(part=>({index:part.sourceIndex,offset:part.offset,text:part.text}));
          const mapped=w.hoshiContinuousGroup.renderedProgress({index:1,progress:.4});
          w.__hoshiContinuousInitialSourceIndex=1;await w.hoshiReader.restoreProgress(mapped);
          const restored=w.hoshiContinuousGroup.restoreSource(mapped);output.restored=[restored.source.index,restored.progress];
        }
        outputs.push(output);
      })).then(()=>document.getElementById('results').textContent=JSON.stringify(outputs)).catch(error=>document.getElementById('results').textContent=JSON.stringify({error:String(error),stack:error.stack}));
      <\/script></body></html>`;
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'hoshi-continuous-browser-'));
    const file = path.join(directory, 'fixture.html');
    fs.writeFileSync(file, html);
    const result = spawnSync(chromium, ['--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-background-networking', `--user-data-dir=${path.join(directory, 'profile')}`, '--virtual-time-budget=6000', '--dump-dom', pathToFileURL(file).href], { encoding: 'utf8', windowsHide: true, timeout: 30000, maxBuffer: 8 * 1024 * 1024 });
    assert.equal(result.status, 0, result.error?.message || result.stderr);
    const payload = result.stdout.match(/<pre id="results">(.*?)<\/pre>/s)?.[1];
    assert.ok(payload && payload !== 'pending', 'Browser fixture did not complete');
    const results = JSON.parse(payload.replaceAll('&quot;', '"').replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>'));
    assert.ok(Array.isArray(results), JSON.stringify(results));
    assert.equal(results.length, 8);
    for (const output of results) {
        assert.equal(output.buffers, output.imageOnly ? 0 : 1);
        if (output.imageOnly) continue;
        assert.ok(Math.abs(output.bufferLength - 240) < 0.5, JSON.stringify(output));
        if (output.singleton) continue;
        assert.deepEqual(output.colors, ['rgb(255, 0, 0)', 'rgb(0, 0, 255)']);
        assert.notEqual(output.fonts[0], output.fonts[1]);
        assert.ok(output.fonts[0].includes('hoshi-source-0-font-0') && output.fonts[0].includes('hoshi-source-0-font-1'));
        assert.notEqual(output.animations[0], output.animations[1]);
        assert.deepEqual(output.highlightParts, [{ index: 0, offset: 1, text: 'lpha猫' }, { index: 2, offset: 0, text: 'Be' }]);
        assert.ok(output.afterEnd >= output.beforeEnd && output.afterEnd <= 1);
        assert.equal(output.restored[0], 1);
        assert.ok(Math.abs(output.restored[1] - 0.4) < 1e-9);
    }
});
