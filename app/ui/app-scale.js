// Whole-window scale for the Gallery window. Tile size stays on Ctrl + wheel,
// and a photo's own zoom stays on the wheel inside the viewer.
var lodiAppScale = (function () {
  var MIN = 0.5, MAX = 2, STEP = 0.1;
  function clampAppScale(n) {
    if (typeof n !== 'number' || !isFinite(n)) return 1;
    var stepped = Math.round(n * 10) / 10;
    return Math.min(MAX, Math.max(MIN, stepped));
  }
  function appScaleFromKey(current, key) {
    if (key === '0') return 1;
    if (key === '+' || key === '=') return clampAppScale(current + STEP);
    if (key === '-' || key === '_') return clampAppScale(current - STEP);
    return null;
  }
  return { MIN: MIN, MAX: MAX, STEP: STEP, clampAppScale: clampAppScale, appScaleFromKey: appScaleFromKey };
})();
if (typeof module === 'object' && module.exports) module.exports = lodiAppScale;
