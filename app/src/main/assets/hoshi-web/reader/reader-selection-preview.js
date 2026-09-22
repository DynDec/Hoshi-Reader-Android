(function(global) {
  // Reader-only wiring between shared selection and the popup host. Keep this
  // separate from chapter setup so upstream setup changes do not own callbacks.
  global.hoshiReaderSelectionPreview = {
    configure: function(configuration) {
      global.hoshiSelection?.configure?.({
        previewSelection: function(selectionApi) {
          var payload = {
            rects: selectionApi.selectionRects(1),
            darkMode: configuration.darkMode,
            eInkMode: configuration.eInkMode,
            verticalWriting: configuration.verticalWriting
          };
          if (global.hoshiReaderPopupHost?.previewRootSelection) {
            global.hoshiReaderPopupHost.previewRootSelection(payload);
          } else {
            global.__hoshiPendingReaderSelectionPreview = payload;
          }
        },
        clearSelectionPreview: function() {
          global.__hoshiPendingReaderSelectionPreview = null;
          global.hoshiReaderPopupHost?.clearRootSelectionPreview?.();
        }
      });
    }
  };
})(window);
