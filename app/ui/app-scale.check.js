const test = require('node:test');
const assert = require('node:assert/strict');
const { appScaleFromKey, clampAppScale } = require('./app-scale.js');

test('plus and equals step up by 10 points', () => {
  assert.equal(appScaleFromKey(1, '+'), 1.1);
  assert.equal(appScaleFromKey(1, '='), 1.1);
});

test('minus and underscore step down by 10 points', () => {
  assert.equal(appScaleFromKey(1, '-'), 0.9);
  assert.equal(appScaleFromKey(1, '_'), 0.9);
});

test('zero returns to 100%', () => {
  assert.equal(appScaleFromKey(1.6, '0'), 1);
});

test('other keys are left alone', () => {
  assert.equal(appScaleFromKey(1, 'k'), null);
});

test('the scale stays between 50% and 200%', () => {
  assert.equal(appScaleFromKey(2, '+'), 2);
  assert.equal(appScaleFromKey(0.5, '-'), 0.5);
  assert.equal(clampAppScale(Number.NaN), 1);
});
