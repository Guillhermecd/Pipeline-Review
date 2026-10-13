import dayjs from 'dayjs';
import type { FieldKey, Validation } from '../modules/types';
import { currency, parseMoney, percent, shortDate } from '../../utils/format';

// Regras de domínio do backend, reproduzidas aqui só para o modo mock.
// No sistema real elas vivem no Spring Boot e a UI apenas exibe o resultado.

const FIRST_DIGIT_WEIGHTS = [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];
const SECOND_DIGIT_WEIGHTS = [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];
const CNPJ_LENGTH = 14;
const CENT_TOLERANCE = 0.01;
const REQUIRED_FIELD_COUNT = 5;

const FIELD_WEIGHTS: Record<FieldKey, number> = {
  cnpj: 0.25,
  total: 0.25,
  items: 0.2,
  issueDate: 0.15,
  issuer: 0.15,
};

const checkDigit = (digits: number[], weights: number[]) => {
  const remainder = digits.reduce((sum, digit, i) => sum + digit * weights[i], 0) % 11;
  return remainder < 2 ? 0 : 11 - remainder;
};

export const cnpjCheckDigits = (base: number[]): [number, number] => {
  const first = checkDigit(base, FIRST_DIGIT_WEIGHTS);
  return [first, checkDigit([...base, first], SECOND_DIGIT_WEIGHTS)];
};

export const formatCnpj = (digits: string) =>
  `${digits.slice(0, 2)}.${digits.slice(2, 5)}.${digits.slice(5, 8)}/${digits.slice(8, 12)}-${digits.slice(12)}`;

const validateCnpj = (text: string): Validation => {
  const name = 'Dígito verificador do CNPJ';
  const digits = text.replace(/\D/g, '').split('').map(Number);
  if (digits.length !== CNPJ_LENGTH) {
    return { name, ok: false, detail: `${digits.length} dígitos, esperado ${CNPJ_LENGTH}` };
  }
  const [first, second] = cnpjCheckDigits(digits.slice(0, 12));
  const ok = first === digits[12] && second === digits[13];
  const detail = ok
    ? `DV ${first}${second} confere`
    : `DV calculado ${first}${second}, lido ${digits[12]}${digits[13]}`;
  return { name, ok, detail };
};

type ValidationInput = { cnpj: string; total: string; itemsSum: number; issueDate: string };

export const validateDocument = (input: ValidationInput): Validation[] => {
  const total = parseMoney(input.total);
  const sumMatches = Math.abs(input.itemsSum - total) <= CENT_TOLERANCE;
  const notFuture = !dayjs(input.issueDate).isAfter(dayjs(), 'day');
  return [
    validateCnpj(input.cnpj),
    {
      name: 'Soma dos itens = total',
      ok: sumMatches,
      detail: `${currency(input.itemsSum)} vs ${currency(total)}`,
    },
    { name: 'Data não futura', ok: notFuture, detail: shortDate(input.issueDate) },
    {
      name: 'Campos obrigatórios',
      ok: true,
      detail: `${REQUIRED_FIELD_COUNT} de ${REQUIRED_FIELD_COUNT} presentes`,
    },
  ];
};

export const weightedConfidence = (fields: Record<FieldKey, number>) =>
  (Object.keys(FIELD_WEIGHTS) as FieldKey[]).reduce(
    (score, key) => score + fields[key] * FIELD_WEIGHTS[key],
    0,
  );

export type Decision = { outcome: 'auto' | 'review'; reason: string };

export const decide = (
  validations: Validation[],
  confidence: number,
  threshold: number,
): Decision => {
  const failed = validations.filter((validation) => !validation.ok);
  if (failed.length) {
    return {
      outcome: 'review',
      reason: 'Validação falhou: ' + failed.map((validation) => validation.name).join(', '),
    };
  }
  if (confidence < threshold) {
    return {
      outcome: 'review',
      reason: `Confiança ${percent(confidence)} abaixo do limiar ${percent(threshold)}`,
    };
  }
  return { outcome: 'auto', reason: `Confiança ${percent(confidence)} e validações ok` };
};
