# Probe findings — `delivery-record`

## 1. The DEPLOYED store honours `ALTER TABLE … DROP COLUMN` (2026-11-10)

Measured against the **production** bunny Database from a workstation via `secrets-env` (full-access token), with
throwaway probes that create and drop run-id-tagged scratch tables (`_probe_dropcol_<run>`, `_probe_strict_<run>`;
no production row or table touched, none left behind). The scratch table mirrored what migration 0017 meets: a
`STRICT` table with an `AUTOINCREMENT` key, a foreign key with `ON DELETE CASCADE`, an index, a nullable and a
`NOT NULL` column.

```
   json_set copies a column into a JSON field       {"v":"0.14"}
   DROP COLUMN of a nullable column                 accepted
   DROP COLUMN of a NOT NULL column                 accepted
   the row and remaining columns survive            yes, values intact
   AUTOINCREMENT continues past the old max         yes (1, 2, 3)
   the cascade still holds after the drop           yes
   STRICT: text into INTEGER, before the drop       rejected
   STRICT: text into INTEGER, after the drop        rejected
   scratch tables left                              0
```

**Settles:** migration 0017 can drop columns in place (design D10); no table rebuild is needed, and a drop leaves the
table's `STRICT` enforcement, its sequence and its foreign keys as they were. (A first run reported STRICT "not
enforced" for `42.5` into a TEXT column — SQLite's STRICT converts a value losslessly convertible to the column's
type, so that check proved nothing; the re-run above uses a value that cannot convert.)

**Expiry trigger:** re-run after any bunny Database platform announcement (it is in public preview), and before
a later migration that drops a column from a table shaped differently from the one probed.
