import type { LineItem, Validation } from '../../api/modules/types';
import { color, font, mono } from '../../theme';
import { currency, decimal, quantity, roundCents } from '../../utils/format';

export function ValidationGrid({ validations }: { validations: Validation[] }) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit,minmax(min(100%,180px),1fr))',
        gap: 8,
      }}
    >
      {validations.map((validation) => (
        <div
          key={validation.name}
          style={{
            display: 'flex',
            gap: 10,
            padding: '10px 12px',
            borderRadius: 8,
            background: validation.ok ? color.okbg : color.errbg,
          }}
        >
          <span
            style={{ ...mono(13), fontWeight: 600, color: validation.ok ? color.ok : color.err }}
            aria-hidden="true"
          >
            {validation.ok ? '✓' : '✕'}
          </span>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
            <span style={{ fontSize: 13, fontWeight: 500 }}>
              {validation.name}
              <span style={{ position: 'absolute', left: -9999 }}>
                {validation.ok ? ' — ok' : ' — falhou'}
              </span>
            </span>
            <span style={{ ...mono(11), color: color.muted }}>{validation.detail}</span>
          </div>
        </div>
      ))}
    </div>
  );
}

const ITEM_COLUMNS = 'minmax(0,1fr) 44px 70px 80px';

export function ItemsTable({ items, itemsSum }: { items: LineItem[]; itemsSum: number }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', fontSize: 13 }}>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: ITEM_COLUMNS,
          gap: 8,
          padding: '6px 0',
          color: color.muted,
          fontSize: 12,
          borderBottom: `1px solid ${color.line}`,
        }}
      >
        <span>Item</span>
        <span style={{ textAlign: 'right' }}>Qtd</span>
        <span style={{ textAlign: 'right' }}>Unit.</span>
        <span style={{ textAlign: 'right' }}>Subtotal</span>
      </div>
      {items.map((item) => (
        <div
          key={item.description}
          style={{
            display: 'grid',
            gridTemplateColumns: ITEM_COLUMNS,
            gap: 8,
            padding: '6px 0',
            borderBottom: `1px solid ${color.line2}`,
            ...mono(12),
          }}
        >
          <span style={{ fontFamily: font.sans, fontSize: 13 }}>{item.description}</span>
          <span style={{ textAlign: 'right' }}>{quantity(item.quantity)}</span>
          <span style={{ textAlign: 'right' }}>{decimal(item.unitPrice)}</span>
          <span style={{ textAlign: 'right' }}>
            {decimal(roundCents(item.quantity * item.unitPrice))}
          </span>
        </div>
      ))}
      <div
        style={{
          display: 'flex',
          justifyContent: 'flex-end',
          gap: 16,
          padding: '8px 0',
          ...mono(12.5),
        }}
      >
        <span style={{ color: color.muted }}>soma dos itens</span>
        <span>{currency(itemsSum)}</span>
      </div>
    </div>
  );
}
