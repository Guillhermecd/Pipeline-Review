import dayjs from 'dayjs';

const MILLIS_PER_SECOND = 1000;

export const roundCents = (value: number) => Math.round(value * 100) / 100;

export const decimal = (value: number) => value.toFixed(2).replace('.', ',');

export const currency = (value: number) =>
  'R$ ' + decimal(value).replace(/\B(?=(\d{3})+(?!\d))/g, '.');

export const percent = (ratio: number) => `${Math.round(ratio * 100)}%`;

export const duration = (ms: number) =>
  ms >= MILLIS_PER_SECOND
    ? `${(ms / MILLIS_PER_SECOND).toFixed(1).replace('.', ',')}s`
    : `${Math.round(ms)}ms`;

export const clockTime = (date: Date = new Date()) => dayjs(date).format('HH:mm:ss');

export const shortDate = (isoDate: string) => dayjs(isoDate).format('DD/MM/YYYY');

export const quantity = (value: number) => String(value).replace('.', ',');

export const parseMoney = (text: string) => {
  const normalized = text
    .replace(/[^\d,.-]/g, '')
    .replace(/\./g, '')
    .replace(',', '.');
  const value = parseFloat(normalized);
  return Number.isNaN(value) ? 0 : value;
};
