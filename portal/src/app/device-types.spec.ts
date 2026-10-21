import { keyValue, typeSpec } from './device-types';

describe('device-types', () => {
  it('formats the key value per type', () => {
    expect(keyValue('thermostat', { ts: 0, data: { temperature: 21.44 } })).toBe('21.4 °C');
    expect(keyValue('relay', { ts: 0, data: { on: true, power: 61.27 } })).toBe('on · 61.3 W');
    expect(keyValue('relay', null)).toBe('—');
  });

  it('offers type-specific plus common commands', () => {
    expect(typeSpec('thermostat').commands.map((c) => c.name)).toEqual([
      'setTargetTemp',
      'setInterval',
      'reboot',
    ]);
    expect(typeSpec('relay').commands.map((c) => c.name)).toContain('setRelay');
  });

  it('falls back to common commands for unknown types', () => {
    expect(typeSpec('toaster').commands.map((c) => c.name)).toEqual(['setInterval', 'reboot']);
  });
});
