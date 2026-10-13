"""How a normalised value may appear printed on a Brazilian fiscal document."""

from datetime import date
from decimal import Decimal, InvalidOperation


def money_variants(amount: str) -> tuple[str, ...]:
    """'1283.80' -> ('1.283,80', '1283,80', '1283.80'). Empty when `amount` is not a decimal."""
    try:
        value = Decimal(amount)
    except InvalidOperation:
        return ()
    plain = f"{value:.2f}"
    integer, cents = plain.split(".")
    grouped = f"{int(integer):,}".replace(",", ".")
    return tuple(dict.fromkeys((f"{grouped},{cents}", f"{integer},{cents}", plain)))


def date_variants(iso_date: str) -> tuple[str, ...]:
    """'2026-09-28' -> ('28/09/2026', '28-09-2026', '28.09.2026', '28/09/26', '2026-09-28')."""
    try:
        parsed = date.fromisoformat(iso_date)
    except ValueError:
        return ()
    day, month, year = f"{parsed.day:02d}", f"{parsed.month:02d}", f"{parsed.year:04d}"
    return (
        f"{day}/{month}/{year}",
        f"{day}-{month}-{year}",
        f"{day}.{month}.{year}",
        f"{day}/{month}/{year[2:]}",
        iso_date,
    )


def quantity_variants(quantity: str) -> tuple[str, ...]:
    """'38.2' -> ('38,2', '38.2', '38,200', '38.200'); '5' -> ('5',)."""
    try:
        value = Decimal(quantity)
    except InvalidOperation:
        return ()
    if value == value.to_integral_value():
        return (str(int(value)),)
    trimmed = format(value.normalize(), "f")
    padded = f"{value:.3f}"
    return tuple(dict.fromkeys((trimmed.replace(".", ","), trimmed, padded.replace(".", ","), padded)))
