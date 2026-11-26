import test from 'node:test';
import assert from 'node:assert/strict';
import { sourceNote } from '../src/utils/sourceNote.js';

test('labels full article complete only when completeness is verified', () => {
  assert.match(sourceNote('FULL_ARTICLE', 'COMPLETE'), /complete article body/);
  assert.match(sourceNote('FULL_ARTICLE', 'UNKNOWN'), /Completeness not verified/);
});

test('labels partial and truncated excerpts accurately', () => {
  assert.match(sourceNote('EXCERPT', 'PARTIAL'), /partial publisher excerpt/);
  assert.match(sourceNote('EXCERPT', 'TRUNCATED'), /cut at the configured limit/);
});

test('labels title only with unknown completeness honestly', () => {
  assert.match(sourceNote('TITLE_ONLY', 'UNKNOWN'), /Completeness not verified/);
});
