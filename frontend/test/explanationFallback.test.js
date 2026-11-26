import test from 'node:test';
import assert from 'node:assert/strict';
import { explanationFallback } from '../src/utils/explanationFallback.js';

test('missing recommendations do not imply the source said no action is required', () => {
  assert.equal(explanationFallback('recommendedAction'), 'Not available in the provided text.');
  assert.doesNotMatch(explanationFallback('recommendedAction'), /no specific action is required/i);
});

test('missing cause and personal benefit remain explicit', () => {
  assert.match(explanationFallback('whyItHappened'), /does not say why/i);
  assert.match(explanationFallback('usefulToReader'), /does not describe a specific personal benefit/i);
});
