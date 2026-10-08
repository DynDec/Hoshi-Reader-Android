import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

// Fixtures use the production Kotlin document composer, CSS and setup generator.
const fixturePath = process.env.HOSHI_TEST_READER_FIXTURES;
test('Production navigation/setup keeps the buffer scrollable and SVG illustrations visible', { skip: !fixturePath }, async () => {
    const fixtures = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
    const loader = `<html><body><pre id="results">pending</pre><script>
      const fixtures=${JSON.stringify(fixtures).replaceAll('</script', '<\\/script')};
      Promise.all(fixtures.map(async(fixture,index)=>{
        const frame=document.createElement('iframe');frame.width=360;frame.height=640;document.body.appendChild(frame);
        const loaded=new Promise(resolve=>frame.onload=resolve);frame.src='/fixture/'+index;await loaded;
        const w=frame.contentWindow,d=w.document,errors=[];
        w.addEventListener('error',e=>errors.push(String(e.error||e.message)));
        w.HoshiReaderRestore={postMessage:()=>w.fixtureReady=true};
        try{w.eval(fixture.setup)}catch(e){errors.push(String(e))}
        for(let i=0;i<200&&!w.fixtureReady;i++)await new Promise(resolve=>setTimeout(resolve,20));
        const buffer=d.querySelector('[data-hoshi-continuous-buffer]');
        const rect=buffer&&buffer.getBoundingClientRect();
        const root=d.scrollingElement||d.documentElement;
        const extent=()=>fixture.vertical?root.scrollWidth:root.scrollHeight;
        const withBuffer=extent();if(buffer)buffer.remove();const withoutBuffer=extent();
        const illustrations=Array.from(d.querySelectorAll('svg')).filter(s=>s.querySelector('image'));
        const decoded=await Promise.all(Array.from(d.querySelectorAll('svg image')).map(async element=>{
          const img=new w.Image();img.src=element.getAttribute('href')||element.getAttribute('xlink:href');
          try{await img.decode();return img.naturalWidth>0&&img.naturalHeight>0}catch{return false}
        }));
        const svg=illustrations.map(s=>{const r=s.getBoundingClientRect(),source=s.closest('[data-hoshi-source-index]')||d.body,owner=source.getBoundingClientRect();return {width:r.width,height:r.height,visible:w.getComputedStyle(s).visibility,sourceWidth:owner.width,sourceHeight:owner.height,sourceBlockLength:fixture.vertical?owner.width:owner.height}});
        const sources=w.hoshiContinuousGroup&&w.hoshiContinuousGroup.sources;
        const imageSpacing=(sources||[]).map(source=>{
          const owner=source.element,body=owner.querySelector('[data-hoshi-source-body]');
          if(!body)return {expected:0,before:0,after:0};
          const outer=owner.getBoundingClientRect(),inner=body.getBoundingClientRect();
          return {expected:sources.length>1&&!source.hasText?24:0,
            before:fixture.vertical?outer.right-inner.right:inner.top-outer.top,
            after:fixture.vertical?inner.left-outer.left:outer.bottom-inner.bottom};
        });
        return {indices:fixture.indices,merge:fixture.merge,vertical:fixture.vertical,hasText:fixture.hasText,ready:!!w.fixtureReady,errors,
          expectedSvgImages:fixture.expectedSvgImages,svgImages:d.querySelectorAll('svg image').length,decoded,
          buffers:d.querySelectorAll('[data-hoshi-continuous-buffer]').length+(buffer?1:0),bufferLength:rect&&(fixture.vertical?rect.width:rect.height),
          addedExtent:withBuffer-withoutBuffer,withoutBuffer,viewport:fixture.vertical?w.innerWidth:w.innerHeight,svg,sourceCount:sources&&sources.length,imageSpacing};
      })).then(results=>document.getElementById('results').textContent=JSON.stringify(results))
        .catch(e=>document.getElementById('results').textContent=JSON.stringify({error:String(e),stack:e.stack}));
      <\/script></body></html>`;
    const server = http.createServer((request, response) => {
        const match = request.url.match(/^\/fixture\/(\d+)$/);
        if (match) {
            const fixture = fixtures[Number(match[1])];
            response.setHeader('Content-Type', `${fixture.mime}; charset=utf-8`); response.end(fixture.html);
        } else { response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(loader); }
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'hoshi-production-browser-'));
    try {
        const child = spawn(process.env.HOSHI_TEST_CHROMIUM, ['--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
            '--disable-background-networking', `--user-data-dir=${path.join(directory, 'profile')}`, '--virtual-time-budget=12000',
            '--dump-dom', `http://127.0.0.1:${server.address().port}/`], { windowsHide: true });
        let stdout='',stderr='';child.stdout.on('data',data=>stdout+=data);child.stderr.on('data',data=>stderr+=data);
        const timer=setTimeout(()=>child.kill(),45000);
        const code=await new Promise((resolve,reject)=>{child.on('exit',resolve);child.on('error',reject)});clearTimeout(timer);
        assert.equal(code,0,stderr.slice(-2000));
        const payload=stdout.match(/<pre id="results">(.*?)<\/pre>/s)?.[1];
        assert.ok(payload&&payload!=='pending','Browser fixtures did not complete');
        const results=JSON.parse(payload.replaceAll('&quot;','"').replaceAll('&amp;','&').replaceAll('&lt;','<').replaceAll('&gt;','>'));
        assert.ok(Array.isArray(results),JSON.stringify(results));
        fs.writeFileSync(path.join(path.dirname(fixturePath),'browser-results.json'),JSON.stringify(results,null,2));
        const failures=[];
        for(const output of results){
            if(!output.ready||output.errors.length)failures.push({reason:'initialization',...output});
            if(output.buffers!==(output.hasText?1:0))failures.push({reason:'buffer count',...output});
            if(output.hasText&&Math.abs(output.bufferLength-360)>.5)failures.push({reason:'15em size',...output});
            // A long text document must gain the full buffer length in the scrolling direction.
            if(output.hasText&&output.withoutBuffer>output.viewport&&output.addedExtent<359)failures.push({reason:'buffer scroll extent',...output});
            if(output.svg.some(s=>s.width<1||s.height<1||s.visible!=='visible'))failures.push({reason:'SVG collapsed',...output});
            if(output.svg.some(s=>s.sourceBlockLength<1))failures.push({reason:'SVG source collapsed',...output});
            if(output.svgImages!==output.expectedSvgImages)failures.push({reason:'SVG image lost',...output});
            if(output.decoded.some(value=>!value))failures.push({reason:'SVG resource failed to decode',...output});
            if(output.sourceCount!==output.indices.split(',').length)failures.push({reason:'source mapping',...output});
            if(output.imageSpacing.some(s=>Math.abs(s.before-s.expected)>.5||Math.abs(s.after-s.expected)>.5))failures.push({reason:'merged image 1em spacing',...output});
        }
        assert.deepEqual(failures,[]);
    } finally { await new Promise(resolve=>server.close(resolve)); }
});
