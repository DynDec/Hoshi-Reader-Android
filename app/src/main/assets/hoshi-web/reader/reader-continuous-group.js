(function(global) {
  'use strict';
  var group = {
    sources: [],
    reader: null,
    install: function(reader, config) {
      this.reader = reader;
      var self = this;
      this.sources = (config || []).map(function(source) {
        return Object.assign({}, source, {
          element: document.querySelector('[data-hoshi-source-index="' + source.index + '"]') || document.body
        });
      });
      if (!this.sources.length) return;
      this.offsetsReady = false;
      if (this.offsetsObserver) this.offsetsObserver.disconnect();
      this.offsetsObserver = typeof global.MutationObserver === 'function'
        ? new global.MutationObserver(function() { self.offsetsReady = false; })
        : null;
      if (this.offsetsObserver) this.offsetsObserver.observe(document.body, { childList: true, characterData: true, subtree: true });
      this.scopeStyles();
      if (this.sources.some(function(source) { return source.hasText; })) {
        var buffer = document.createElement('div');
        buffer.setAttribute('data-hoshi-continuous-buffer', '');
        buffer.style.cssText = 'all:initial!important;display:block!important;writing-mode:inherit!important;block-size:15em!important;inline-size:1px!important;margin:0!important;padding:0!important;flex-shrink:0!important;break-inside:avoid!important;';
        buffer.style.setProperty('font-size', global.getComputedStyle(document.body).fontSize, 'important');
        document.body.appendChild(buffer);
      }
      this.wrapHighlights();
      this.wrapSelection();
      var applyCues = reader.applySasayakiCues;
      if (applyCues) reader.applySasayakiCues = function(cues, preserve) {
        return applyCues.call(this, (cues || []).map(function(cue) { return self.renderedCue(cue); }), preserve);
      };
      ['highlightSasayakiCue', 'sasayakiMediaStopsBeforeCue'].forEach(function(name) {
        var method = reader[name];
        if (method) reader[name] = function(cue) {
          var args = Array.from(arguments); args[0] = self.renderedCue(cue);
          return method.apply(this, args);
        };
      });
      var chapterEnd = reader.sasayakiMediaStopsToChapterEnd;
      if (chapterEnd) reader.sasayakiMediaStopsToChapterEnd = function() {
        var position = self.sourcePosition(), source = position && self.source(position.index);
        if (!source || self.sources.length === 1) return chapterEnd.call(this);
        var current = this.sasayakiScrollPosition(), vertical = this.isVertical(), seen = new Set();
        var stops = this.sasayakiMediaElements().filter(function(element) { return source.element.contains(element); })
          .map(function(element) { return reader.sasayakiMediaScrollForElement(element); })
          .filter(function(scroll) {
            if (!Number.isFinite(scroll) || (vertical ? scroll > current + 0.5 : scroll < current - 0.5) || seen.has(scroll)) return false;
            seen.add(scroll); return true;
          }).sort(function(a, b) { return vertical ? b - a : a - b; });
        return stops.map(function(scroll) { return { scroll: scroll }; });
      };
    },
    scopeStyles: function() {
      var names = new Map();
      document.querySelectorAll('style[data-hoshi-source-style]').forEach(function(template) {
        var scope = template.getAttribute('data-hoshi-source-style');
        var scoped = names.get(scope) || { fonts: new Map(), animations: new Map() };
        names.set(scope, scoped);
        function collect(list) {
          Array.from(list || []).forEach(function(rule) {
            if (/^@font-face/i.test(rule.cssText)) {
              var family = rule.style.getPropertyValue('font-family').trim().replace(/^['"]|['"]$/g, '');
              if (family && !scoped.fonts.has(family)) scoped.fonts.set(family, scope + '-font-' + scoped.fonts.size);
            }
            if (/^@(?:-webkit-)?keyframes/i.test(rule.cssText)) {
              if (!scoped.animations.has(rule.name)) scoped.animations.set(rule.name, scope + '-animation-' + scoped.animations.size);
            }
            else if (rule.cssRules) collect(rule.cssRules);
          });
        }
        collect(template.sheet && template.sheet.cssRules);
      });
      function replaceNames(value, mapping) {
        mapping.forEach(function(replacement, original) {
            var escaped = original.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
            value = value.replace(new RegExp('(^|[\\s,\'"])' + escaped + '(?=$|[\\s,\'"])', 'g'), '$1' + replacement);
        });
        return value;
      }
      function declarations(style, scoped) {
        Array.from(style || []).forEach(function(property) {
            var value = style.getPropertyValue(property);
            if (property === 'font-family' || property === 'font') value = replaceNames(value, scoped.fonts);
            if (/^(?:-webkit-)?animation(?:-name)?$/.test(property)) value = replaceNames(value, scoped.animations);
            style.setProperty(property, value, style.getPropertyPriority(property));
        });
        return style.cssText;
      }
      document.querySelectorAll('style[data-hoshi-source-style]').forEach(function(template) {
        var scope = template.getAttribute('data-hoshi-source-style');
        var prefix = '#' + scope;
        var scoped = names.get(scope);
        function selector(value) {
          value = value.replace(/(^|[\s>+~,(])(?:html|:root)#([\w\u0080-\uffff-]+)/g, '$1' + prefix + '[data-hoshi-publisher-root-id="$2"]');
          value = value.replace(/#((?:\\[\da-f]{1,6}\s?|\\.|[\w\u0080-\uffff-])+)/gi, function(_, id) { return '#' + scope + '-' + id; });
          value = value.replaceAll('#' + scope + '-' + scope, prefix);
          value = value.replace(/(^|[\s>+~,(])(?:html|:root)(?=$|[\s>+~.#:[\]])/g, '$1' + prefix)
            .replace(/(^|[\s>+~,(])body(?=$|[\s>+~.#:[\]])/g, '$1[data-hoshi-source-body]');
          if (value.trim().startsWith(prefix)) return value;
          return (/^[.#:\[]/.test(value.trim()) ? prefix + value + ',' : '') + prefix + ' ' + value;
        }
        function rules(list) {
          return Array.from(list || []).map(function(rule) {
            if (rule.selectorText) return rule.selectorText.split(/,(?![^()]*\))/).map(selector).join(',') + '{' + declarations(rule.style, scoped) + '}';
            if (/^@(?:-webkit-)?keyframes/i.test(rule.cssText)) {
              return '@keyframes ' + scoped.animations.get(rule.name) + '{' + Array.from(rule.cssRules).map(function(frame) { return frame.keyText + '{' + declarations(frame.style, scoped) + '}'; }).join('') + '}';
            }
            if (rule.cssRules && !/keyframes/i.test(rule.cssText)) {
              return rule.cssText.slice(0, rule.cssText.indexOf('{') + 1) + rules(rule.cssRules) + '}';
            }
            if (rule.style) return rule.cssText.slice(0, rule.cssText.indexOf('{') + 1) + declarations(rule.style, scoped) + '}';
            return rule.cssText;
          }).join('');
        }
        var style = document.createElement('style');
        style.textContent = rules(template.sheet && template.sheet.cssRules);
        var media = template.getAttribute('data-hoshi-publisher-media');
        if (media) style.media = media;
        document.head.appendChild(style);
        template.remove();
      });
      names.forEach(function(scoped, scope) {
        var root = document.getElementById(scope);
        if (!root) return;
        [root].concat(Array.from(root.querySelectorAll('[style]'))).forEach(function(element) {
          declarations(element.style, scoped);
        });
      });
    },
    source: function(index) { return this.sources.find(function(source) { return source.index === index; }); },
    offsets: function() {
      // Text node structure only changes through childList/characterData mutations;
      // takeRecords() also catches mutations made earlier in the current task.
      var observer = this.offsetsObserver;
      if (this.offsetsReady && observer && observer.takeRecords().length === 0) return;
      this.offsetsReady = false;
      var reader = this.reader, raw = 0, normalized = 0;
      this.sources.forEach(function(source) {
        source.rawStart = raw; source.normalizedStart = normalized;
        var walker = reader.createWalker(source.element), node;
        while ((node = walker.nextNode())) {
          raw += reader.countRawChars(node.textContent); normalized += reader.countChars(node.textContent);
        }
        source.rawEnd = raw; source.normalizedEnd = normalized;
      });
      this.offsetsReady = true;
    },
    sourceForOffset: function(offset, raw) {
      this.offsets();
      var key = raw ? 'raw' : 'normalized';
      return this.sources.find(function(source) { return offset >= source[key + 'Start'] && offset < source[key + 'End']; }) || this.sources[this.sources.length - 1];
    },
    sourcePosition: function() {
      if (!this.sources.length) return null;
      var reader = this.reader, vertical = reader.isVertical();
      var source = this.sources[0];
      for (var candidate of this.sources) {
        var bounds = candidate.element.getBoundingClientRect();
        if (vertical ? bounds.right >= global.innerWidth - 1 : bounds.top <= 1) source = candidate;
      }
      if (!source) return null;
      var count = 0, explored = 0, node, walker = reader.createWalker(source.element);
      while ((node = walker.nextNode())) {
        count += reader.countChars(node.textContent);
        explored += reader.countCharsBeforeViewport(node, vertical);
      }
      var rect = source.element.getBoundingClientRect();
      var length = vertical ? rect.width : rect.height;
      var distance = vertical ? rect.right - global.innerWidth : -rect.top;
      var progress = count > 0 ? explored / count : (length > 0 ? distance / length : 0);
      if (vertical ? rect.left >= global.innerWidth : rect.bottom <= 0) progress = 1;
      return { index: source.index, progress: Math.max(0, Math.min(1, progress)) };
    },
    renderedProgress: function(position) {
      var total = this.sources.reduce(function(sum, source) { return sum + Math.max(1, source.count); }, 0), before = 0;
      for (var source of this.sources) {
        var weight = Math.max(1, source.count);
        if (source.index === position.index) return (before + weight * position.progress) / total;
        before += weight;
      }
      return 0;
    },
    restoreSource: function(progress) {
      var total = this.sources.reduce(function(sum, source) { return sum + Math.max(1, source.count); }, 0);
      var remaining = Math.max(0, Math.min(1, progress)) * total;
      var before = 0;
      for (var initial of this.sources) {
        var initialWeight = Math.max(1, initial.count);
        if (initial.index === global.__hoshiContinuousInitialSourceIndex && remaining >= before - 1e-9 && remaining <= before + initialWeight + 1e-9) {
          return { source: initial, progress: Math.max(0, Math.min(1, (remaining - before) / initialWeight)) };
        }
        before += initialWeight;
      }
      for (var source of this.sources) {
        var weight = Math.max(1, source.count);
        if (remaining < weight || source === this.sources[this.sources.length - 1]) return { source: source, progress: Math.max(0, Math.min(1, remaining / weight)) };
        remaining -= weight;
      }
      return null;
    },
    renderedCue: function(cue) {
      if (!cue || typeof cue === 'string' || cue.sourceIndex === undefined) return cue;
      this.offsets(); var source = this.source(cue.sourceIndex);
      if (!source) return cue;
      var result = Object.assign({}, cue, { start: source.normalizedStart + cue.start });
      delete result.sourceIndex; return result;
    },
    wrapHighlights: function() {
      var self = this, highlights = global.hoshiHighlights;
      if (!highlights) return;
      var apply = highlights.applyHighlights;
      highlights.applyHighlights = function(items) {
        self.offsets();
        return apply.call(this, (items || []).map(function(item) {
          var source = self.source(item.sourceIndex);
          return source ? Object.assign({}, item, { offset: source.rawStart + item.offset }) : item;
        }));
      };
      var show = highlights.showSearchHighlight;
      highlights.showSourceSearchHighlight = function(index, offset, length) {
        self.offsets(); var source = self.source(index);
        if (source) return show.call(this, source.normalizedStart + offset, length);
      };
      var create = highlights.createHighlight;
      highlights.createHighlight = function(color, id) {
        self.offsets();
        var result = create.call(this, color, id);
        if (!result || result.action !== 'created') return result;
        var start = result.offset, end = start + Array.from(result.text).length;
        var parts = [];
        self.sources.forEach(function(source) {
          var from = Math.max(start, source.rawStart), to = Math.min(end, source.rawEnd);
          if (to <= from) return;
          var text = highlights.textForRange(from, to - from);
          var prefix = highlights.textForRange(source.rawStart, from - source.rawStart).text || '';
          parts.push({ id: parts.length ? id + "-" + source.index : id, sourceIndex: source.index, start: self.reader.countChars(prefix), offset: from - source.rawStart, text: text.text, textFurigana: text.textFurigana });
        });
        if (parts.length) {
          highlights.removeHighlight(id);
          parts.forEach(function(part) {
            var source = self.source(part.sourceIndex);
            highlights.wrapHighlight({id:part.id,color:color,offset:source.rawStart + part.offset,text:part.text});
          });
          self.reader.buildNodeOffsets();
        }
        return Object.assign({}, result, { parts: parts });
      };
    },
    wrapSelection: function() {
      var self = this, selection = global.hoshiSelection;
      if (!selection || !selection.postTextSelectedAfterPaint) return;
      var post = selection.postTextSelectedAfterPaint;
      selection.postTextSelectedAfterPaint = function(payload) {
        var args = Array.from(arguments);
        if (payload.normalizedOffset !== null && payload.normalizedOffset !== undefined) {
          var source = self.sourceForOffset(payload.normalizedOffset, false);
          if (source) args[0] = Object.assign({}, payload, { sourceIndex: source.index, normalizedOffset: payload.normalizedOffset - source.normalizedStart });
        }
        return post.apply(this, args);
      };
    }
  };
  global.hoshiContinuousGroup = group;
})(window);
