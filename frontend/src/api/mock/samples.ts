import type { SampleDocument } from '../modules/types';
import { roundCents } from '../../utils/format';
import { RAW_SAMPLES } from './fixtures';
import { cnpjCheckDigits, formatCnpj } from './rules';

const PASSING_SCENARIO = 'Deve aprovar';

export const SAMPLES: SampleDocument[] = RAW_SAMPLES.map((raw) => {
  const digits = cnpjCheckDigits(raw.cnpjBase.split('').map(Number));
  if (raw.corruptCnpj) digits[1] = (digits[1] + 1) % 10;
  const itemsSum = roundCents(
    raw.items.reduce((sum, item) => sum + item.quantity * item.unitPrice, 0),
  );
  return {
    key: raw.key,
    fileName: raw.fileName,
    kind: raw.kind,
    issuer: raw.issuer,
    cnpj: formatCnpj(raw.cnpjBase + digits.join('')),
    issueDate: raw.issueDate,
    items: raw.items,
    itemsSum,
    total: roundCents(itemsSum + (raw.totalOffset ?? 0)),
    baseConfidence: raw.baseConfidence,
    scenario: raw.scenario,
    expectedToPass: raw.scenario === PASSING_SCENARIO,
  };
});
