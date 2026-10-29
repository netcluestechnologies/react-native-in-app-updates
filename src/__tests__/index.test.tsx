import { describe, it, expect } from '@jest/globals';
import { checkVersion } from '../index';

describe('checkVersion', () => {
  it('is exported as a function', () => {
    expect(typeof checkVersion).toBe('function');
  });
});
