---
title: Multi-Currency
nav_order: 2
parent: Features
---

# Multi-Currency Support

Overview


Configuration

- Define currencies in `config.yml` under the `currencies` section. Each entry includes:
  - `id` — internal identifier (lowercase, no spaces)
  - `display` — human-friendly name
  - `symbol` — short symbol shown in formatted outputs
  - `decimals` — number of decimal places (use integers; server-side arithmetic uses `BigDecimal` or scaled long)

Usage

- Commands and API methods accept an optional currency parameter. If omitted, the server's default currency is used.
- Examples:
  - `/balance` — shows balance in default currency.
  - `/balance coins` — shows balance in `coins` currency.

API

- The public API exposes currency-aware methods such as `getBalance(player, currencyId)`, `deposit(player, amount, currencyId)`, and `format(amount, currencyId)`.

Best practices


See also

- [docs/integration/vault.md](docs/integration/vault.md)
