(function(global) {
  'use strict';

  function pixelValue(value) {
    var parsed = parseFloat(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function hasGeneratedContent(style) {
    var content = style && style.content;
    return !!content && content !== 'none' && content !== 'normal' && content !== '""' && content !== "''";
  }

  function isMeaningfulEmptySpan(element) {
    if (element.hasAttribute && (element.hasAttribute('id') || element.hasAttribute('name'))) {
      return true;
    }
    var style = global.getComputedStyle(element);
    if (style.backgroundImage && style.backgroundImage !== 'none') {
      return true;
    }
    return hasGeneratedContent(global.getComputedStyle(element, '::before'))
      || hasGeneratedContent(global.getComputedStyle(element, '::after'));
  }

  function sanitizeInlineBlocks(scope, vertical) {
    var doc = scope && scope.body ? scope : global.document;
    if (!doc || !doc.body || !doc.querySelectorAll || !global.getComputedStyle) return;

    var bodyStyle = global.getComputedStyle(doc.body);
    var blockExtent = vertical
      ? doc.body.clientWidth - pixelValue(bodyStyle.paddingLeft) - pixelValue(bodyStyle.paddingRight)
      : doc.body.clientHeight - pixelValue(bodyStyle.paddingTop) - pixelValue(bodyStyle.paddingBottom);
    var inlineExtent = vertical
      ? doc.body.clientHeight - pixelValue(bodyStyle.paddingTop) - pixelValue(bodyStyle.paddingBottom)
      : doc.body.clientWidth - pixelValue(bodyStyle.paddingLeft) - pixelValue(bodyStyle.paddingRight);

    function blockSize(element) {
      var rect = element.getBoundingClientRect();
      return vertical ? rect.width : rect.height;
    }

    function inlineSize(element) {
      var rect = element.getBoundingClientRect();
      return vertical ? rect.height : rect.width;
    }

    Array.from(doc.querySelectorAll('div, span')).forEach(function(element) {
      if (global.getComputedStyle(element).display !== 'inline-block' || !element.querySelector('p')) {
        return;
      }
      if (inlineSize(element) <= inlineExtent + 1 && blockSize(element) <= blockExtent + 1) {
        return;
      }
      element.style.setProperty('display', 'block', 'important');
      if (!element.parentNode || !element.parentNode.querySelectorAll) return;
      element.parentNode.querySelectorAll('span:empty').forEach(function(strut) {
        if (
          strut.parentNode === element.parentNode
          && !isMeaningfulEmptySpan(strut)
          && global.getComputedStyle(strut).display === 'inline-block'
        ) {
          strut.style.setProperty('display', 'none', 'important');
        }
      });
    });

    Array.from(doc.querySelectorAll('span:empty')).forEach(function(element) {
      if (
        !isMeaningfulEmptySpan(element)
        && global.getComputedStyle(element).display === 'inline-block'
        && blockSize(element) > blockExtent
      ) {
        element.style.setProperty(vertical ? 'width' : 'height', blockExtent + 'px', 'important');
      }
    });
  }

  var FURIGANA_MIN_SCALE = 0.30;
  var FURIGANA_MAX_SCALE = 0.45;
  var FURIGANA_SCALE_STEP = 0.005;
  var FURIGANA_ADVANCE_TOLERANCE = 0.5;
  var typographyProperties = [
    'font-family',
    'font-size',
    'font-weight',
    'font-style',
    'font-variant',
    'font-stretch',
    'font-feature-settings',
    'font-variation-settings',
    'font-kerning',
    'letter-spacing',
    'word-spacing',
    'line-height',
    'text-orientation',
    'text-combine-upright',
    'ruby-position',
    'white-space',
    'text-rendering',
    'writing-mode'
  ];

  function styleValue(style, property) {
    if (!style) return '';
    if (typeof style.getPropertyValue === 'function') {
      var value = style.getPropertyValue(property);
      if (value !== undefined && value !== null && String(value) !== '') return String(value);
    }
    var name = property.replace(/-([a-z])/g, function(_, letter) { return letter.toUpperCase(); });
    return style[name] === undefined || style[name] === null ? '' : String(style[name]);
  }

  function setStyleValue(style, property, value, priority) {
    if (!style || value === undefined || value === null || value === '') return;
    if (typeof style.setProperty === 'function') {
      style.setProperty(property, String(value), priority || '');
      return;
    }
    var name = property.replace(/-([a-z])/g, function(_, letter) { return letter.toUpperCase(); });
    style[name] = String(value);
  }

  function copyTypography(fromStyle, toElement) {
    if (!fromStyle || !toElement || !toElement.style) return;
    typographyProperties.forEach(function(property) {
      setStyleValue(toElement.style, property, styleValue(fromStyle, property));
    });
  }

  function typographyKey(baseStyle, rubyStyle, rtStyle) {
    return [baseStyle, rubyStyle, rtStyle].map(function(style) {
      return typographyProperties.map(function(property) {
        return property + '=' + styleValue(style, property);
      }).join(';');
    }).join('|');
  }

  function elementTag(element) {
    return element && element.tagName ? String(element.tagName).toLowerCase() : '';
  }

  function textOutsideRubyAnnotation(node) {
    if (!node) return '';
    if (node.nodeType === 3) return node.nodeValue || node.textContent || '';
    if (node.nodeType !== 1) return '';
    var tag = elementTag(node);
    if (tag === 'rt' || tag === 'rp') return '';
    var text = '';
    Array.from(node.childNodes || []).forEach(function(child) {
      text += textOutsideRubyAnnotation(child);
    });
    return text;
  }

  function firstBaseElement(ruby) {
    var found = null;
    function visit(node) {
      if (found || !node || node.nodeType !== 1) return;
      var tag = elementTag(node);
      if (tag === 'rt' || tag === 'rp') return;
      if (tag !== 'ruby') {
        found = node;
        return;
      }
      Array.from(node.childNodes || []).forEach(visit);
    }
    visit(ruby);
    return found || ruby;
  }

  function visibleStyle(style) {
    if (!style) return true;
    var display = styleValue(style, 'display').toLowerCase();
    var visibility = styleValue(style, 'visibility').toLowerCase();
    var opacity = parseFloat(styleValue(style, 'opacity'));
    return display !== 'none'
      && visibility !== 'hidden'
      && visibility !== 'collapse'
      && (!Number.isFinite(opacity) || opacity > 0);
  }

  function visibleRubyReading(ruby, getStyle) {
    if (ruby.classList && ruby.classList.contains('furigana-hidden')) return null;
    if (!visibleStyle(getStyle(ruby))) return null;
    if (typeof ruby.getClientRects === 'function' && ruby.getClientRects().length === 0) return null;
    var annotations = Array.from(ruby.querySelectorAll ? ruby.querySelectorAll('rt') : []);
    var reading = '';
    var visible = false;
    annotations.forEach(function(annotation) {
      if (!visibleStyle(getStyle(annotation))) return;
      var text = annotation.textContent || '';
      if (!text.trim()) return;
      visible = true;
      reading += text;
    });
    return visible ? reading : null;
  }

  function scaleCss(scale) {
    return scale.toFixed(3).replace(/0+$/, '').replace(/\.$/, '') + 'em';
  }

  function scaleValues() {
    var values = [];
    for (var value = FURIGANA_MIN_SCALE; value <= FURIGANA_MAX_SCALE + 0.0001; value += FURIGANA_SCALE_STEP) {
      values.push(Number(value.toFixed(3)));
    }
    return values;
  }

  function cloneRuby(doc, ruby, baseText, readingText) {
    if (ruby && typeof ruby.cloneNode === 'function') return ruby.cloneNode(true);
    var result = doc.createElement('ruby');
    var base = doc.createElement('span');
    base.textContent = baseText;
    var annotation = doc.createElement('rt');
    annotation.textContent = readingText;
    result.appendChild(base);
    result.appendChild(annotation);
    return result;
  }

  function configureProbeElement(element, style) {
    copyTypography(style, element);
    setStyleValue(element.style, 'display', 'block');
    setStyleValue(element.style, 'writing-mode', 'vertical-rl');
    setStyleValue(element.style, 'white-space', 'nowrap');
    setStyleValue(element.style, 'box-sizing', 'content-box');
    setStyleValue(element.style, 'margin', '0');
    setStyleValue(element.style, 'padding', '0');
    setStyleValue(element.style, 'border', '0');
    setStyleValue(element.style, 'min-width', '0');
    setStyleValue(element.style, 'min-height', '0');
    setStyleValue(element.style, 'max-width', 'none');
    setStyleValue(element.style, 'max-height', 'none');
    setStyleValue(element.style, 'overflow', 'visible');
    setStyleValue(element.style, 'width', 'max-content');
    setStyleValue(element.style, 'height', 'max-content');
    setStyleValue(element.style, 'column-count', '1');
    setStyleValue(element.style, 'column-width', 'auto');
    setStyleValue(element.style, 'column-gap', '0');
  }

  function measureAdvance(element) {
    if (!element) return 0;
    var rect = typeof element.getBoundingClientRect === 'function'
      ? element.getBoundingClientRect()
      : null;
    var advance = rect ? Number(rect.width) : 0;
    if (!(advance > 0) && typeof element.offsetWidth === 'number') {
      advance = Number(element.offsetWidth);
    }
    return Number.isFinite(advance) && advance > 0 ? advance : 0;
  }

  function measureTypographyGroup(doc, group, scale) {
    var probe = null;
    try {
      probe = doc.createElement('div');
      if (!probe || !probe.style || !doc.body || typeof doc.body.appendChild !== 'function') return null;
      if (probe.classList) probe.classList.add('hoshi-furigana-calibration-probe');
      else setStyleValue(probe.style, 'class', 'hoshi-furigana-calibration-probe');
      setStyleValue(probe.style, 'position', 'absolute');
      setStyleValue(probe.style, 'left', '-100000px');
      setStyleValue(probe.style, 'top', '0');
      setStyleValue(probe.style, 'visibility', 'hidden');
      setStyleValue(probe.style, 'pointer-events', 'none');
      setStyleValue(probe.style, '--hoshi-furigana-scale', scaleCss(scale));
      configureProbeElement(probe, group.baseStyle);

      var plainLine = doc.createElement('div');
      plainLine.typographyGroup = group.ruby.typographyGroup;
      configureProbeElement(plainLine, group.baseStyle);
      var plainText = doc.createElement('span');
      plainText.textContent = group.baseText;
      plainLine.appendChild(plainText);

      var rubyLine = doc.createElement('div');
      rubyLine.typographyGroup = group.ruby.typographyGroup;
      configureProbeElement(rubyLine, group.baseStyle);
      var ruby = cloneRuby(doc, group.ruby, group.baseText, group.readingText);
      copyTypography(group.rubyStyle, ruby);
      var annotations = Array.from(ruby.querySelectorAll ? ruby.querySelectorAll('rt') : []);
      annotations.forEach(function(annotation) {
        copyTypography(group.rtStyle, annotation);
        setStyleValue(annotation.style, 'font-size', scaleCss(scale));
      });
      rubyLine.appendChild(ruby);
      probe.appendChild(plainLine);
      probe.appendChild(rubyLine);
      doc.body.appendChild(probe);
      return {
        plainAdvance: measureAdvance(plainLine),
        rubyAdvance: measureAdvance(rubyLine)
      };
    } catch (_) {
      return null;
    } finally {
      if (probe && probe.parentNode && typeof probe.parentNode.removeChild === 'function') {
        probe.parentNode.removeChild(probe);
      }
    }
  }

  function calibrateVerticalRubySize(scope, vertical) {
    var doc = scope && scope.body ? scope : global.document;
    var root = doc && doc.documentElement;
    var defaultScale = scaleCss(FURIGANA_MAX_SCALE);
    if (!doc || !doc.body || !root || !root.style || !global.getComputedStyle) return null;

    if (typeof root.style.removeProperty === 'function') {
      root.style.removeProperty('--hoshi-furigana-scale');
    }
    if (!vertical) return null;
    try {
      var getStyle = function(element) { return global.getComputedStyle(element); };
      var rubyElements = Array.from(doc.body.querySelectorAll ? doc.body.querySelectorAll('ruby') : []);
      var groups = new Map();
      rubyElements.forEach(function(ruby) {
        var readingText = visibleRubyReading(ruby, getStyle);
        if (!readingText) return;
        var baseText = textOutsideRubyAnnotation(ruby).trim();
        if (!baseText) return;
        var baseElement = firstBaseElement(ruby);
        var group = {
          ruby: ruby,
          baseText: baseText,
          readingText: readingText,
          baseStyle: getStyle(baseElement),
          rubyStyle: getStyle(ruby),
          rtStyle: getStyle(ruby.querySelector('rt')),
          score: readingText.length / Math.max(1, baseText.length)
        };
        var key = typographyKey(group.baseStyle, group.rubyStyle, group.rtStyle);
        var previous = groups.get(key);
        if (!previous || group.score > previous.score
          || (group.score === previous.score && readingText.length > previous.readingText.length)) {
          groups.set(key, group);
        }
      });
      if (!groups.size) return null;

      setStyleValue(root.style, '--hoshi-furigana-scale', defaultScale);

      var groupList = Array.from(groups.values());
      var baseline = groupList.map(function(group) {
        return measureTypographyGroup(doc, group, FURIGANA_MAX_SCALE);
      });
      if (!baseline.length || baseline.some(function(metrics) {
        return !metrics || !(metrics.plainAdvance > 0) || !(metrics.rubyAdvance > 0);
      })) return defaultScale;

      var values = scaleValues();
      var bestIndex = -1;
      var low = 0;
      var high = values.length - 1;
      while (low <= high) {
        var middle = Math.floor((low + high) / 2);
        var candidate = values[middle];
        var fits = true;
        var baselineIndex = 0;
        groupList.forEach(function(group) {
          if (!fits) return;
          var metrics;
          if (candidate === FURIGANA_MAX_SCALE) {
            metrics = baseline[baselineIndex++];
          } else {
            metrics = measureTypographyGroup(doc, group, candidate);
          }
          if (!metrics || !(metrics.plainAdvance > 0) || !(metrics.rubyAdvance > 0)
            || metrics.rubyAdvance > metrics.plainAdvance + FURIGANA_ADVANCE_TOLERANCE) {
            fits = false;
          }
        });
        if (fits) {
          bestIndex = middle;
          low = middle + 1;
        } else {
          high = middle - 1;
        }
      }
      var selected = bestIndex >= 0 ? values[bestIndex] : FURIGANA_MIN_SCALE;
      setStyleValue(root.style, '--hoshi-furigana-scale', scaleCss(selected));
      return scaleCss(selected);
    } catch (_) {
      setStyleValue(root.style, '--hoshi-furigana-scale', defaultScale);
      return defaultScale;
    }
  }

  function fitVerticalPaginatedFurigana(scope, options) {
    if (typeof options === 'boolean') return calibrateVerticalRubySize(scope, options);
    var configuration = options || {};
    var paginated = configuration.paginated !== false && configuration.mode !== 'continuous';
    var vertical = configuration.vertical !== undefined ? configuration.vertical : true;
    if (!paginated || !vertical) {
      return calibrateVerticalRubySize(scope, false);
    }
    if (configuration.visibleRuby === false || configuration.furiganaVisible === false || configuration.hideFurigana === true) {
      var doc = scope && scope.body ? scope : global.document;
      var root = doc && doc.documentElement;
      if (root && root.style && typeof root.style.removeProperty === 'function') {
        root.style.removeProperty('--hoshi-furigana-scale');
      }
      return null;
    }
    return calibrateVerticalRubySize(scope, true);
  }

  global.hoshiReaderLayoutSemantics = {
    sanitizeInlineBlocks: sanitizeInlineBlocks,
    fitVerticalPaginatedFurigana: fitVerticalPaginatedFurigana
  };
})(window);
