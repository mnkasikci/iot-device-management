import { Component, computed, input } from '@angular/core';

/**
 * Tiny line chart as plain SVG — no chart library needed for "how did this value move recently".
 * Scales the values to the box; a flat series is drawn in the middle.
 */
@Component({
  selector: 'app-sparkline',
  template: `
    <svg [attr.viewBox]="'0 0 ' + width + ' ' + height" [attr.width]="width" [attr.height]="height">
      <polyline [attr.points]="points()" fill="none" stroke="currentColor" stroke-width="1.5" />
    </svg>
  `,
  host: { class: 'sparkline' },
})
export class Sparkline {
  readonly values = input.required<number[]>();
  readonly width = 160;
  readonly height = 36;

  readonly points = computed(() => {
    const values = this.values();
    if (values.length < 2) return '';
    const min = Math.min(...values);
    const max = Math.max(...values);
    const range = max - min || 1;
    const pad = 2;
    return values
      .map((v, i) => {
        const x = (i / (values.length - 1)) * this.width;
        const y =
          max === min ? this.height / 2 : pad + (1 - (v - min) / range) * (this.height - 2 * pad);
        return `${x.toFixed(1)},${y.toFixed(1)}`;
      })
      .join(' ');
  });
}
