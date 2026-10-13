import { NavLink } from 'react-router-dom';
import { fixed, mono } from '../../theme';

export type NavItem = { to: string; label: string; badge?: number };

const groupLabel = (paddingTop: number) => ({
  ...mono(10.5),
  color: fixed.sidebarLabel,
  letterSpacing: '.08em',
  padding: `${paddingTop}px 8px 6px`,
});

function NavEntry({ item }: { item: NavItem }) {
  return (
    <NavLink
      to={item.to}
      style={({ isActive }) => ({
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        padding: '9px 10px',
        borderRadius: 7,
        textDecoration: 'none',
        background: isActive ? fixed.sidebarActive : 'transparent',
        color: isActive ? fixed.white : fixed.sidebarTextSoft,
      })}
    >
      {({ isActive }) => (
        <>
          <span style={{ flex: 1 }}>{item.label}</span>
          <span style={{ ...mono(11), color: isActive ? fixed.accent : fixed.sidebarLabel }}>
            {item.badge || ''}
          </span>
        </>
      )}
    </NavLink>
  );
}

type SidebarNavProps = { execution: NavItem[]; product: NavItem[] };

export function SidebarNav({ execution, product }: SidebarNavProps) {
  return (
    <nav style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div style={groupLabel(0)}>EXECUÇÃO</div>
      {execution.map((item) => (
        <NavEntry key={item.to} item={item} />
      ))}
      <div style={groupLabel(18)}>PRODUTO</div>
      {product.map((item) => (
        <NavEntry key={item.to} item={item} />
      ))}
    </nav>
  );
}
