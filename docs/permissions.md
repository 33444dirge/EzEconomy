---
title: Permissions
nav_order: 5
---

# Permissions

Assign permissions through your permissions plugin (LuckPerms, PermissionsEx, GroupManager, etc.).

## Player Permissions

Note: New installs should prefer the `ezeconomy.user.*` namespace (for example `ezeconomy.user.pay`). Legacy `ezeconomy.*` nodes remain supported for backwards compatibility.

| Permission | Description | Default |
| --- | --- | --- |
| `ezeconomy.balance` | View own balance with `/balance`. | everyone |
| `ezeconomy.balance.others` | View another player's balance. | op |
| `ezeconomy.pay` | Send money to another player with `/pay`. | everyone |
| `ezeconomy.payall` | Use `/pay * <amount>` to pay all online players. | op |
| `ezeconomy.currency` | View or change preferred currency with `/currency`. | everyone |
| `ezeconomy.baltop` | View the top-balance list with `/baltop`. | everyone |

## Administrative Permissions

| Permission | Description | Default |
| --- | --- | --- |
| `ezeconomy.eco` | Umbrella admin permission for economy operations (legacy). Prefer granting granular sub-permissions below. | op |
| `ezeconomy.eco.give` | Allows `/eco give <player> <amount>`. | op |
| `ezeconomy.eco.take` | Allows `/eco take <player> <amount>`. | op |
| `ezeconomy.eco.set` | Allows `/eco set <player> <amount>`. | op |
| `ezeconomy.eco.gui` | Allows opening the `/eco` GUI and using `/eco` to open user GUI when enabled. | everyone |
| `ezeconomy.admin` | Run all `/ezeconomy` subcommands (reload, cleanup, database). | op |
| `ezeconomy.payall.bypasswithdraw` | Use `/pay *` without funds being withdrawn from the sender. | op |


| Permission | Description |
| --- | --- |

## Recommended Role Setup

| Role | Permissions to grant |
| --- | --- |
| **Moderator** | All player permissions + `ezeconomy.balance.others` |

## LuckPerms Example

```bash
# Grant the default player group economy access
lp group default permission set ezeconomy.pay true
lp group default permission set ezeconomy.currency true
lp group default permission set ezeconomy.baltop true

# Grant moderators balance inspection
lp group moderator permission set ezeconomy.balance.others true

# Grant admins full economy control
lp group admin permission set ezeconomy.eco true
lp group admin permission set ezeconomy.admin true
```
