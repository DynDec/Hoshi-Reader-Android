import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const layoutSemanticsUrl = new URL(
    '../../main/assets/hoshi-web/reader/reader-layout-semantics.js',
    import.meta.url,
);

class TestStyle {
    constructor() {
        this.values = new Map();
        this.priorities = new Map();
    }

    setProperty(name, value, priority = '') {
        this.values.set(name, String(value));
        this.priorities.set(name, priority);
    }

    getPropertyValue(name) {
        return this.values.get(name) ?? '';
    }

    getPropertyPriority(name) {
        return this.priorities.get(name) ?? '';
    }

    removeProperty(name) {
        const value = this.values.get(name) ?? '';
        this.values.delete(name);
        this.priorities.delete(name);
        return value;
    }
}

class TestElement {
    constructor(tagName, rect = {}) {
        this.tagName = tagName.toUpperCase();
        this.childNodes = [];
        this.parentNode = null;
        this.style = new TestStyle();
        this.attributes = new Map();
        this.rect = {
            width: rect.width ?? 0,
            height: rect.height ?? 0,
        };
        this.computed = {
            display: 'block',
            paddingLeft: '0px',
            paddingRight: '0px',
            paddingTop: '0px',
            paddingBottom: '0px',
        };
    }

    appendChild(child) {
        child.parentNode = this;
        this.childNodes.push(child);
        return child;
    }

    getAttribute(name) {
        return this.attributes.get(name) ?? null;
    }

    setAttribute(name, value) {
        this.attributes.set(name, String(value));
    }

    hasAttribute(name) {
        return this.attributes.has(name);
    }

    querySelector(selector) {
        return this.querySelectorAll(selector)[0] ?? null;
    }

    querySelectorAll(selector) {
        const result = [];
        const visit = (node) => {
            node.childNodes.forEach((child) => {
                if (selector === 'p' && child.tagName === 'P') result.push(child);
                if (selector === 'span:empty' && child.tagName === 'SPAN' && child.childNodes.length === 0) {
                    result.push(child);
                }
                visit(child);
            });
        };
        visit(this);
        return result;
    }

    getBoundingClientRect() {
        return this.rect;
    }
}

function loadLayoutSemantics(elements, body) {
    const document = {
        body,
        querySelectorAll(selector) {
            if (selector === 'div, span') {
                return elements.filter((element) => element.tagName === 'DIV' || element.tagName === 'SPAN');
            }
            if (selector === 'span:empty') {
                return elements.filter((element) => element.tagName === 'SPAN' && element.childNodes.length === 0);
            }
            return [];
        },
    };
    const window = {
        document,
        getComputedStyle(element, pseudoElement) {
            if (pseudoElement === '::before') return element.beforeComputed ?? { content: 'none' };
            if (pseudoElement === '::after') return element.afterComputed ?? { content: 'none' };
            return {
                ...element.computed,
                display: element.style.getPropertyValue('display') || element.computed.display,
            };
        },
    };
    const source = fs.existsSync(layoutSemanticsUrl)
        ? fs.readFileSync(layoutSemanticsUrl, 'utf8')
        : '';
    vm.runInNewContext(source, { document, window });
    return { document, layout: window.hoshiReaderLayoutSemantics };
}

function matchesTestSelector(element, selector) {
    return selector.split(',').some((part) => {
        const value = part.trim();
        const classTag = value.match(/^([a-z]+)?\.([\w-]+)$/i);
        if (classTag) {
            return (!classTag[1] || element.tagName === classTag[1].toUpperCase())
                && element.className.split(/\s+/).includes(classTag[2]);
        }
        const notClass = value.match(/^([a-z]+):not\(\.([\w-]+)\)$/i);
        if (notClass) {
            return element.tagName === notClass[1].toUpperCase()
                && !element.className.split(/\s+/).includes(notClass[2]);
        }
        return /^[a-z]+$/i.test(value) && element.tagName === value.toUpperCase();
    });
}

class CalibrationText {
    constructor(value) {
        this.nodeType = 3;
        this.nodeValue = String(value);
        this.parentNode = null;
    }

    get textContent() {
        return this.nodeValue;
    }

    set textContent(value) {
        this.nodeValue = String(value);
    }
}

class CalibrationElement {
    constructor(tagName, fixture) {
        this.nodeType = 1;
        this.tagName = tagName.toUpperCase();
        this.childNodes = [];
        this.parentNode = null;
        this.fixture = fixture;
        this.style = new TestStyle();
        this.className = '';
        this.computed = {
            display: 'block',
            fontFamily: fixture.defaultTypography.fontFamily,
            fontSize: `${fixture.defaultTypography.fontSize}px`,
            fontWeight: fixture.defaultTypography.fontWeight,
            fontStyle: fixture.defaultTypography.fontStyle,
            lineHeight: String(fixture.defaultTypography.lineHeight),
            letterSpacing: fixture.defaultTypography.letterSpacing,
            wordSpacing: fixture.defaultTypography.wordSpacing,
            writingMode: 'vertical-rl',
        };
        this.classList = {
            add: (...names) => {
                const classes = new Set(this.className.split(/\s+/).filter(Boolean));
                names.forEach((name) => classes.add(name));
                this.className = [...classes].join(' ');
            },
            remove: (...names) => {
                const remove = new Set(names);
                this.className = this.className
                    .split(/\s+/)
                    .filter((name) => name && !remove.has(name))
                    .join(' ');
            },
            contains: (name) => this.className.split(/\s+/).includes(name),
        };
    }

    appendChild(child) {
        child.parentNode = this;
        if (child.nodeType === 1 && !child.typographyGroup && this.typographyGroup) {
            child.typographyGroup = this.typographyGroup;
        }
        this.childNodes.push(child);
        return child;
    }

    removeChild(child) {
        const index = this.childNodes.indexOf(child);
        if (index >= 0) {
            this.childNodes.splice(index, 1);
            child.parentNode = null;
        }
        return child;
    }

    get textContent() {
        return this.childNodes.map((node) => node.textContent || '').join('');
    }

    set textContent(value) {
        this.childNodes = [];
        if (value !== '') this.appendChild(new CalibrationText(value));
    }

    querySelector(selector) {
        return this.querySelectorAll(selector)[0] ?? null;
    }

    querySelectorAll(selector) {
        const result = [];
        const visit = (node) => {
            if (node.nodeType === 1 && matchesTestSelector(node, selector)) result.push(node);
            node.childNodes?.forEach(visit);
        };
        visit(this);
        return result;
    }

    cloneNode(deep = false) {
        const clone = new CalibrationElement(this.tagName, this.fixture);
        clone.className = this.className;
        clone.computed = { ...this.computed };
        clone.typographyGroup = this.typographyGroup;
        clone.style = new TestStyle();
        this.style.values.forEach((value, name) => clone.style.setProperty(name, value, this.style.getPropertyPriority(name)));
        if (deep) this.childNodes.forEach((child) => clone.appendChild(
            child.nodeType === 1 ? child.cloneNode(true) : new CalibrationText(child.nodeValue),
        ));
        return clone;
    }

    getBoundingClientRect() {
        return this.fixture.rectFor(this);
    }

}

class CalibrationFixture {
    constructor(groups) {
        this.groups = groups;
        this.defaultTypography = {
            fontFamily: 'Test Serif',
            fontSize: 20,
            fontWeight: '400',
            fontStyle: 'normal',
            lineHeight: 1.65,
            letterSpacing: '0px',
            wordSpacing: '0px',
        };
        this.document = null;
    }

    groupFor(element) {
        if (element.typographyGroup && this.groups[element.typographyGroup]) return element.typographyGroup;
        const computed = element.computed || {};
        const match = Object.entries(this.groups).find(([, group]) => (
            (group.fontFamily ?? this.defaultTypography.fontFamily) === computed.fontFamily
            && `${group.fontSize ?? this.defaultTypography.fontSize}px` === computed.fontSize
            && String(group.lineHeight ?? this.defaultTypography.lineHeight) === computed.lineHeight
        ));
        return match?.[0] || Object.keys(this.groups)[0];
    }

    scaleFor(element) {
        if (element.tagName === 'RUBY') {
            const annotation = element.querySelector('rt');
            const value = annotation?.style?.getPropertyValue('font-size');
            const match = String(value || '').match(/([0-9.]+)\s*em/i);
            return match ? Number(match[1]) : 0.45;
        }
        let node = element;
        while (node) {
            const value = node.style?.getPropertyValue('font-size') || node.style?.fontSize;
            const match = String(value || '').match(/([0-9.]+)\s*em/i);
            if (match) return Number(match[1]);
            node = node.parentNode;
        }
        return 0.45;
    }

    rectFor(element) {
        const groupName = this.groupFor(element);
        const group = this.groups[groupName] || {};
        const plain = Number(group.plainAdvance ?? (group.fontSize ?? 20) * (group.lineHeight ?? 1.65));
        const scale = this.scaleFor(element);
        const ruby = element.tagName === 'RUBY' || element.tagName === 'RT';
        let advance = plain;
        if (ruby) {
            const threshold = Number(group.fitScale ?? 0.45);
            advance = plain + Math.max(0, scale - threshold) * 100 + 0.1;
        }
        if (element.childNodes?.length && element.tagName !== 'RT' && element.tagName !== 'RUBY') {
            const childAdvances = element.childNodes
                .filter((child) => child.nodeType === 1)
                .map((child) => this.rectFor(child));
            if (childAdvances.length) advance = Math.max(...childAdvances.map((rect) => rect.width));
        }
        return { left: 0, right: advance, top: 0, bottom: 3, width: advance, height: 3 };
    }

}

function loadCalibrationLayout(groups) {
    const fixture = new CalibrationFixture(groups);
    const body = new CalibrationElement('body', fixture);
    const documentElement = new CalibrationElement('html', fixture);
    documentElement.style = new TestStyle();
    documentElement.appendChild(body);
    fixture.document = {
        body,
        documentElement,
        createElement(tagName) {
            return new CalibrationElement(tagName, fixture);
        },
        querySelectorAll(selector) {
            return documentElement.querySelectorAll(selector);
        },
    };
    const window = {
        document: fixture.document,
        getComputedStyle(element, pseudoElement) {
            if (pseudoElement === '::before' || pseudoElement === '::after') return { content: 'none' };
            const computed = { ...element.computed };
            element.style.values.forEach((value, name) => {
                const field = {
                    'font-size': 'fontSize',
                    'font-family': 'fontFamily',
                    'font-weight': 'fontWeight',
                    'font-style': 'fontStyle',
                    'line-height': 'lineHeight',
                    'letter-spacing': 'letterSpacing',
                    'word-spacing': 'wordSpacing',
                }[name];
                if (field) computed[field] = value;
            });
            computed.display = element.style.getPropertyValue('display') || computed.display;
            computed.writingMode = element.style.getPropertyValue('writing-mode') || computed.writingMode;
            computed.getPropertyValue = (name) => {
                const field = name.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
                return element.style.getPropertyValue(name) || computed[name] || computed[field] || '';
            };
            return computed;
        },
    };
    const source = fs.readFileSync(layoutSemanticsUrl, 'utf8');
    vm.runInNewContext(source, { document: fixture.document, window });
    return { document: fixture.document, body, root: documentElement, layout: window.hoshiReaderLayoutSemantics, fixture };
}

function appendRuby(document, groupName, reading = 'かんじ') {
    const ruby = document.createElement('ruby');
    ruby.typographyGroup = groupName;
    const group = document.body.fixture.groups[groupName] || {};
    ruby.computed = {
        ...ruby.computed,
        fontFamily: group.fontFamily ?? ruby.computed.fontFamily,
        fontSize: `${group.fontSize ?? document.body.fixture.defaultTypography.fontSize}px`,
        lineHeight: String(group.lineHeight ?? document.body.fixture.defaultTypography.lineHeight),
    };
    ruby.appendChild(new CalibrationText('漢'));
    const rt = document.createElement('rt');
    rt.typographyGroup = groupName;
    rt.computed = { ...ruby.computed };
    rt.appendChild(new CalibrationText(reading));
    ruby.appendChild(rt);
    document.body.appendChild(ruby);
    return ruby;
}

function scaleFrom(document) {
    return document.documentElement.style.getPropertyValue('--hoshi-furigana-scale');
}

function fitOptions(overrides = {}) {
    return {
        mode: 'paginated',
        paginated: true,
        vertical: true,
        visibleRuby: true,
        furiganaVisible: true,
        hideFurigana: false,
        ...overrides,
    };
}

function probeCount(document) {
    return document.body.childNodes.filter((node) => node.tagName === 'DIV').length;
}

test('vertical ruby calibration skips non-paginated or non-vertical layouts', () => {
    const horizontal = loadCalibrationLayout({ normal: { fitScale: 0.30 } });
    appendRuby(horizontal.document, 'normal');
    horizontal.document.documentElement.style.setProperty('--hoshi-furigana-scale', '0.3em');
    horizontal.layout.fitVerticalPaginatedFurigana(horizontal.document, fitOptions({ vertical: false }));
    assert.equal(scaleFrom(horizontal.document), '');
    assert.equal(probeCount(horizontal.document), 0);

    const continuous = loadCalibrationLayout({ normal: { fitScale: 0.30 } });
    appendRuby(continuous.document, 'normal');
    continuous.document.documentElement.style.setProperty('--hoshi-furigana-scale', '0.3em');
    continuous.layout.fitVerticalPaginatedFurigana(
        continuous.document,
        fitOptions({ mode: 'continuous', paginated: false }),
    );
    assert.equal(scaleFrom(continuous.document), '');
    assert.equal(probeCount(continuous.document), 0);
});

test('vertical ruby calibration skips chapters without visible ruby annotations', () => {
    const fixture = loadCalibrationLayout({ normal: { fitScale: 0.30 } });
    appendRuby(fixture.document, 'normal');
    fixture.layout.fitVerticalPaginatedFurigana(
        fixture.document,
        fitOptions({ visibleRuby: false, furiganaVisible: false, hideFurigana: true }),
    );
    assert.equal(scaleFrom(fixture.document), '');
    assert.equal(probeCount(fixture.document), 0);

    const hiddenRuby = loadCalibrationLayout({ normal: { fitScale: 0.30 } });
    const ruby = appendRuby(hiddenRuby.document, 'normal');
    ruby.classList.add('furigana-hidden');
    ruby.querySelector('rt').computed.display = 'none';
    hiddenRuby.layout.fitVerticalPaginatedFurigana(hiddenRuby.document, fitOptions());
    assert.equal(scaleFrom(hiddenRuby.document), '');
    assert.equal(probeCount(hiddenRuby.document), 0);
});

test('vertical ruby calibration retains the default scale when 0.45em fits', () => {
    const fixture = loadCalibrationLayout({ normal: { fitScale: 0.45 } });
    appendRuby(fixture.document, 'normal');

    fixture.layout.fitVerticalPaginatedFurigana(fixture.document, fitOptions());

    assert.equal(scaleFrom(fixture.document), '0.45em');
    assert.equal(probeCount(fixture.document), 0);
});

test('vertical ruby calibration reduces the scale in 0.005em steps for long readings', () => {
    const fixture = loadCalibrationLayout({ long: { fitScale: 0.40 } });
    appendRuby(fixture.document, 'long', 'かんじのながいよみ');

    fixture.layout.fitVerticalPaginatedFurigana(fixture.document, fitOptions());

    assert.equal(scaleFrom(fixture.document), '0.4em');
    assert.equal(probeCount(fixture.document), 0);
});

test('vertical ruby calibration retains 0.30em when no tested scale fits', () => {
    const fixture = loadCalibrationLayout({ impossible: { fitScale: 0.25 } });
    appendRuby(fixture.document, 'impossible', 'とてもながいふりがな');

    fixture.layout.fitVerticalPaginatedFurigana(fixture.document, fitOptions());

    assert.equal(scaleFrom(fixture.document), '0.3em');
    assert.equal(probeCount(fixture.document), 0);
});

test('vertical ruby calibration uses one scale for every chapter typography group', () => {
    const fixture = loadCalibrationLayout({
        compact: { fontFamily: 'Compact Serif', fontSize: 18, lineHeight: 1.65, fitScale: 0.45 },
        long: { fontFamily: 'Long Serif', fontSize: 20, lineHeight: 1.75, fitScale: 0.395 },
    });
    appendRuby(fixture.document, 'compact', 'みじかい');
    appendRuby(fixture.document, 'long', 'とてもながいふりがな');

    fixture.layout.fitVerticalPaginatedFurigana(fixture.document, fitOptions());

    assert.equal(scaleFrom(fixture.document), '0.395em');
    assert.equal(probeCount(fixture.document), 0);
});

test('vertical ruby calibration cleans probes and recalculates the chapter scale', () => {
    const fixture = loadCalibrationLayout({ normal: { fitScale: 0.40 } });
    appendRuby(fixture.document, 'normal', 'ながいよみ');

    fixture.layout.fitVerticalPaginatedFurigana(fixture.document, fitOptions());
    assert.equal(scaleFrom(fixture.document), '0.4em');
    assert.equal(probeCount(fixture.document), 0);

    fixture.fixture.groups.normal.fitScale = 0.45;
    fixture.layout.fitVerticalPaginatedFurigana(fixture.document, fitOptions());

    assert.equal(scaleFrom(fixture.document), '0.45em');
    assert.equal(probeCount(fixture.document), 0);
    assert.equal(fixture.document.body.childNodes.length, 1);
});

test('layout sanitizer converts oversized paragraph wrappers and removes their empty inline struts', () => {
    const body = new TestElement('body');
    body.clientWidth = 384;
    body.clientHeight = 832;
    body.computed.paddingBottom = '31px';

    const parent = new TestElement('section');
    const publisherWrapper = new TestElement('div', { width: 870, height: 801 });
    publisherWrapper.computed.display = 'inline-block';
    publisherWrapper.appendChild(new TestElement('p'));
    const emptyStrut = new TestElement('span', { width: 20, height: 801 });
    emptyStrut.computed.display = 'inline-block';
    const fittingWrapper = new TestElement('div', { width: 200, height: 400 });
    fittingWrapper.computed.display = 'inline-block';
    fittingWrapper.appendChild(new TestElement('p'));
    parent.appendChild(publisherWrapper);
    parent.appendChild(emptyStrut);
    parent.appendChild(fittingWrapper);

    const { document, layout } = loadLayoutSemantics(
        [publisherWrapper, emptyStrut, fittingWrapper],
        body,
    );
    assert.equal(typeof layout?.sanitizeInlineBlocks, 'function');

    layout.sanitizeInlineBlocks(document, true);

    assert.equal(publisherWrapper.style.getPropertyValue('display'), 'block');
    assert.equal(publisherWrapper.style.getPropertyPriority('display'), 'important');
    assert.equal(emptyStrut.style.getPropertyValue('display'), 'none');
    assert.equal(emptyStrut.style.getPropertyPriority('display'), 'important');
    assert.equal(fittingWrapper.style.getPropertyValue('display'), '');
});

test('layout sanitizer clamps empty inline struts to the horizontal block extent', () => {
    const body = new TestElement('body');
    body.clientWidth = 600;
    body.clientHeight = 400;
    body.computed.paddingLeft = '20px';
    body.computed.paddingRight = '20px';
    body.computed.paddingTop = '10px';
    body.computed.paddingBottom = '10px';

    const oversizedStrut = new TestElement('span', { width: 40, height: 500 });
    oversizedStrut.computed.display = 'inline-block';
    const fittingStrut = new TestElement('span', { width: 40, height: 300 });
    fittingStrut.computed.display = 'inline-block';

    const { document, layout } = loadLayoutSemantics([oversizedStrut, fittingStrut], body);
    assert.equal(typeof layout?.sanitizeInlineBlocks, 'function');

    layout.sanitizeInlineBlocks(document, false);

    assert.equal(oversizedStrut.style.getPropertyValue('height'), '380px');
    assert.equal(oversizedStrut.style.getPropertyPriority('height'), 'important');
    assert.equal(fittingStrut.style.getPropertyValue('height'), '');
});

test('layout sanitizer preserves semantic anchors, publisher art, and nested empty spans', () => {
    const body = new TestElement('body');
    body.clientWidth = 384;
    body.clientHeight = 832;

    const parent = new TestElement('section');
    const publisherWrapper = new TestElement('div', { width: 870, height: 800 });
    publisherWrapper.computed.display = 'inline-block';
    publisherWrapper.appendChild(new TestElement('p'));

    const anchor = new TestElement('span', { width: 1, height: 1 });
    anchor.computed.display = 'inline-block';
    anchor.setAttribute('id', 'page-12');
    const art = new TestElement('span', { width: 24, height: 24 });
    art.computed.display = 'inline-block';
    art.computed.backgroundImage = 'url(ornament.svg)';
    const generatedArt = new TestElement('span', { width: 24, height: 24 });
    generatedArt.computed.display = 'inline-block';
    generatedArt.beforeComputed = { content: '"◆"' };
    const nested = new TestElement('div');
    const nestedEmpty = new TestElement('span', { width: 8, height: 8 });
    nestedEmpty.computed.display = 'inline-block';
    nested.appendChild(nestedEmpty);

    parent.appendChild(publisherWrapper);
    parent.appendChild(anchor);
    parent.appendChild(art);
    parent.appendChild(generatedArt);
    parent.appendChild(nested);

    const { document, layout } = loadLayoutSemantics(
        [publisherWrapper, anchor, art, generatedArt, nested, nestedEmpty],
        body,
    );
    assert.equal(typeof layout?.sanitizeInlineBlocks, 'function');

    layout.sanitizeInlineBlocks(document, true);

    assert.equal(publisherWrapper.style.getPropertyValue('display'), 'block');
    assert.equal(anchor.style.getPropertyValue('display'), '');
    assert.equal(art.style.getPropertyValue('display'), '');
    assert.equal(generatedArt.style.getPropertyValue('display'), '');
    assert.equal(nestedEmpty.style.getPropertyValue('display'), '');
});
