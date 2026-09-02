/* MengPaw 浏览器 PDF 预览交互 — pdf.js (Apache-2.0) 渲染 + 文字层选取 + 缩放。
   文档数据以 base64 注入 window.__MP_PDF_B64 (见 viewer.html 数据标记)。
   worker / cmaps 走 file:///android_asset 绝对路径; 文本层支持按住拖动选取文字。 */
(function () {
  "use strict";

  var pdfjsLib = window.pdfjsLib;
  if (!pdfjsLib) { return; }

  var B64 = (typeof window.__MP_PDF_B64 === "string") ? window.__MP_PDF_B64 : "";
  var stage = document.querySelector(".pdf-stage");
  var area = document.getElementById("pdfArea");
  var loadingEl = document.getElementById("pdfLoading");
  var errorEl = document.getElementById("pdfError");
  var errorText = document.getElementById("pdfErrorText");
  var progressEl = document.getElementById("pdfProgress");
  var toolbarEl = document.getElementById("pdfToolbar");
  var totalEl = document.getElementById("pdfTotalPages");
  var curEl = document.getElementById("pdfCurPage");
  var zoomLabelEl = document.getElementById("pdfZoomLabel");

  var GAP = 18;            // 页间垂直间距 (CSS px)
  var MIN_SCALE = 0.4;
  var MAX_SCALE = 4.0;
  var ZOOM_STEP = 1.25;

  var doc = null;
  var numPages = 0;
  var cssScale = 1;        // 当前 CSS 缩放
  var fitScale = 1;
  var pageDims = [];       // 1-based: {w1, h1} scale=1 时 CSS 尺寸
  var pageCache = {};      // i -> Promise<page>
  var textCache = {};      // i -> Promise<textContent>
  var nodes = {};          // i -> DOM 节点
  var pending = {};        // i -> true (渲染中)
  var epoch = 0;           // 缩放换代, 使过期异步渲染失效
  var loading = true;

  pdfjsLib.GlobalWorkerOptions.workerSrc =
    "file:///android_asset/pdf_viewer/pdf.worker.min.js";

  function getPage(i) {
    if (!pageCache[i]) { pageCache[i] = doc.getPage(i); }
    return pageCache[i];
  }
  function getText(i) {
    if (!textCache[i]) {
      textCache[i] = getPage(i).then(function (p) { return p.getTextContent(); });
    }
    return textCache[i];
  }

  function showError(msg) {
    loading = false;
    loadingEl.hidden = true;
    toolbarEl.hidden = true;
    errorEl.hidden = false;
    errorText.textContent = msg || "未知错误";
  }

  function describeError(err) {
    if (!err) return "未知错误";
    if (err && err.name === "PasswordException") {
      return "该 PDF 受密码保护，当前暂不支持输入密码。";
    }
    var m = (err && err.message) ? err.message : String(err);
    return "文档解析失败：" + m;
  }

  function base64ToUint8Array(b64) {
    var raw = atob(b64);
    var n = raw.length;
    var bytes = new Uint8Array(n);
    for (var i = 0; i < n; i++) { bytes[i] = raw.charCodeAt(i); }
    return bytes;
  }

  /* ── 布局几何 (1-based tops; top[i] 为第 i 页上沿) ── */
  function computeTops() {
    var tops = [0];
    var acc = 0;
    for (var i = 1; i <= numPages; i++) {
      tops[i] = acc;
      var d = pageDims[i];
      acc += (d.h1 * cssScale) + GAP;
    }
    return { tops: tops, total: acc - GAP + 24 };
  }

  function areaClientWidth() { return area.clientWidth; }

  /* ── 布局全部页面位置 + 区域高度 ── */
  function updateLayout() {
    var geo = computeTops();
    var clientW = areaClientWidth();
    for (var i = 1; i <= numPages; i++) {
      var node = nodes[i];
      if (node) {
        var w = pageDims[i].w1 * cssScale;
        node.style.left = Math.max(0, Math.floor((clientW - w) / 2)) + "px";
        node.style.top = geo.tops[i] + "px";
      }
    }
    area.style.height = geo.total + "px";
  }

  /* ── 懒加载渲染当前可视窗口, 并回收远端页 ── */
  function updateVisible() {
    if (!doc || numPages === 0) { return; }
    var geo = computeTops();
    var scrollTop = stage.scrollTop;
    var vh = stage.clientHeight;
    var first = -1, last = -1;
    for (var i = 1; i <= numPages; i++) {
      var top = geo.tops[i];
      var hh = pageDims[i].h1 * cssScale;
      if (top + hh >= scrollTop && top <= scrollTop + vh) {
        if (first < 0) { first = i; }
        last = i;
      }
    }
    if (first < 0) { first = last = 1; }
    // 扩 1 页缓冲
    var lo = Math.max(1, first - 1);
    var hi = Math.min(numPages, last + 1);

    // 回收窗口外页面
    for (var k in nodes) {
      var idx = +k;
      if (idx < lo || idx > hi) { removePage(idx); }
    }
    renderRange(lo, hi);

    // 当前页指示
    var nearest = first;
    curEl.textContent = String(nearest);
  }

  function removePage(i) {
    var node = nodes[i];
    if (node && node.parentNode) { node.parentNode.removeChild(node); }
    delete nodes[i];
  }

  function renderRange(lo, hi) {
    var myEpoch = epoch;
    var chain = Promise.resolve();
    for (var i = lo; i <= hi; i++) {
      (function (idx) {
        chain = chain.then(function () {
          if (myEpoch !== epoch) { return; }
          if (nodes[idx] || pending[idx]) { return; }
          return renderPage(idx).catch(function () {});
        });
      })(i);
    }
  }

  function renderPage(i) {
    pending[i] = true;
    var myEpoch = epoch;
    var w = pageDims[i].w1 * cssScale;
    var h = pageDims[i].h1 * cssScale;
    return getPage(i).then(function (page) {
      if (myEpoch !== epoch) { return; }
      var geo = computeTops();
      var node = document.createElement("div");
      node.className = "pdf-page";
      node.style.width = w + "px";
      node.style.height = h + "px";
      node.style.left = Math.max(0, Math.floor((areaClientWidth() - w) / 2)) + "px";
      node.style.top = geo.tops[i] + "px";

      var dpr = window.devicePixelRatio || 1;
      var canvas = document.createElement("canvas");
      var cssVp = page.getViewport({ scale: cssScale });
      canvas.width = Math.floor(cssVp.width * dpr);
      canvas.height = Math.floor(cssVp.height * dpr);
      var ctx = canvas.getContext("2d");
      node.appendChild(canvas);

      var textLayer = document.createElement("div");
      textLayer.className = "textLayer";
      node.appendChild(textLayer);

      // 绘制前先挂载 (避免隐藏容器导致部分 WebView 不渲染)
      area.appendChild(node);
      nodes[i] = node;

      page.render({
        canvasContext: ctx,
        viewport: cssVp,
        transform: dpr !== 1 ? [dpr, 0, 0, dpr, 0, 0] : null
      }).promise.then(function () {
        if (myEpoch !== epoch || !nodes[i]) { return; }
        return getText(i).then(function (textContent) {
          if (myEpoch !== epoch || !nodes[i]) { return; }
          var rt = pdfjsLib.renderTextLayer({
            textContent: textContent,
            container: textLayer,
            viewport: cssVp,
            textDivs: []
          });
          return (rt && rt.promise) ? rt.promise : null;
        });
      }).then(function () {
        pending[i] = undefined; delete pending[i];
      }, function () {
        pending[i] = undefined; delete pending[i];
      });
    });
  }

  function setScale(s, recenter) {
    var ns = Math.min(MAX_SCALE, Math.max(MIN_SCALE, s));
    if (ns === cssScale) { return; }
    var anchor = stage.scrollTop + stage.clientHeight / 2;
    cssScale = ns;
    epoch++;
    zoomLabelEl.textContent = Math.round(cssScale * 100) + "%";
    clearAll();
    updateLayout();
    if (recenter && numPages) {
      // 尽量保持视口中央内容位置近似
      stage.scrollTop = anchor;
    }
    updateVisible();
  }

  function clearAll() {
    for (var i = 1; i <= numPages; i++) { removePage(i); }
  }

  function computeFit() {
    if (numPages === 0) { return 1; }
    var w1 = pageDims[1].w1 || 1;
    var avail = areaClientWidth();
    if (avail <= 0) { return 1; }
    return Math.max(MIN_SCALE, Math.min(1, (avail - 8) / w1));
  }

  function zoomOut() { setScale(cssScale / ZOOM_STEP, true); }
  function zoomIn() { setScale(cssScale * ZOOM_STEP, true); }
  function fit() { setScale(fitScale, false); }

  /* ── 初始化: 解析文档 → 载入尺寸 → 布局 → 渲染 ── */
  function init() {
    numPages = doc.numPages;
    totalEl.textContent = String(numPages);
    loadingEl.hidden = true;
    toolbarEl.hidden = false;
    loading = false;

    var loads = [];
    for (var i = 1; i <= numPages; i++) {
      loads.push(getPage(i).then(function (p) {
        var vp = p.getViewport({ scale: 1 });
        return { idx: p.pageNumber, w1: vp.width, h1: vp.height };
      }));
    }
    Promise.all(loads).then(function (res) {
      for (var r = 0; r < res.length; r++) { pageDims[res[r].idx] = res[r]; }
      fitScale = computeFit();
      cssScale = fitScale;
      zoomLabelEl.textContent = Math.round(cssScale * 100) + "%";
      updateLayout();
      updateVisible();
    }, function (err) {
      showError(describeError(err));
    });
  }

  /* ── 事件 ── */
  var ticking = false;
  stage.addEventListener("scroll", function () {
    if (ticking) { return; }
    ticking = true;
    window.requestAnimationFrame(function () {
      updateVisible();
      ticking = false;
    });
  });
  window.addEventListener("resize", function () {
    if (!doc) { return; }
    fitScale = computeFit();
    setScale(fitScale, false);
  });
  document.getElementById("btnZoomIn").addEventListener("click", zoomIn);
  document.getElementById("btnZoomOut").addEventListener("click", zoomOut);
  document.getElementById("btnFit").addEventListener("click", fit);

  if (!B64) { showError("未提供 PDF 数据"); return; }

  loadingEl.hidden = false;
  var task = pdfjsLib.getDocument({
    data: base64ToUint8Array(B64),
    cMapUrl: "file:///android_asset/pdf_viewer/cmaps/",
    cMapPacked: true
  });
  task.onProgress = function (p) {
    if (p && p.total) {
      progressEl.textContent = " " + Math.round((p.loaded / p.total) * 100) + "%";
    }
  };
  task.promise.then(function (d) { doc = d; init(); }, function (err) {
    showError(describeError(err));
  });
})();
