# Localized search in Kingdom Tasks

Design note for usability package U9, written before the interaction protocol changed to version 3. It records what is searched where, in which languages, within which bounds, and what is deliberately not covered.

## Goals and limits

- A player can find a task by the words they see, in their own language, including translations from their resource packs.
- Search never widens what a player may see. Authorization comes first; matching only filters an already-authorized list, and counts and "no match" messages describe only that list.
- Payloads stay bounded, and nothing a client sends about language or search text affects record identity or authority.

## Matching rules (shared)

`SearchText` is used by the server and the client alike. Case, accents and spacing are ignored; punctuation separates words; every query word must occur, in any order. A small explicit alias list lets common words find each other (village / settlement / town, in English, Spanish and Chinese; guild / organization; election / vote / ballot; agreement / treaty / accord; recruit / troop; government / politics). A match in the title ranks before a match in the category, which ranks before a match in the help or detail; equal ranks keep the original order. Operators can also find a task by its id.

## Task catalogue: searched on the client

1. When Kingdom Tasks opens it requests the **index** (`INDEX`). The server answers with the tasks this player may open now — operator tasks only for operators — as id, consequential flag, English title and category, and English help only where no bundled translation key exists. The index is bounded by the task registry (162 tasks in this version) and travels in one reply of at most 60,000 characters.
2. The client renders each title, category and help through the translation keys `task.mcakingdoms.<id>.title|help` and `task.mcakingdoms.category.<slug>`, so the player's language and every enabled resource pack apply, and searches those words plus the English ones locally. Search and paging cost no further requests.
3. Opening a task is still a server request (`TASK`), and the server checks authorization again then. A permission removed after the index arrived therefore cannot be used: the task answers "This task is unavailable to you." The next open of Kingdom Tasks fetches a fresh index.

The older server-side catalogue search (`LIST`) remains for compatibility; it matches English and the three bundled languages.

## Record choices: searched on the server

Choices (settlements, people, agreements, opportunities and so on) are private and can be many, so they are never sent wholesale.

1. A search (`FILTER`) runs on the server over exactly the candidate list the current field would offer this player — the same authorized list, not a global pool.
2. Each label and detail is a translatable component. The server reads it in every language this jar bundles (English, Spanish, Simplified Chinese) and matches the query against all of those readings, so a Spanish or Chinese player's words match. Player-authored names are literal and match as written.
3. Results are ranked as above and returned in pages of 20. A label is cut to 256 characters and a detail to 1,024 for display; the whole reply stays within the protocol limit.
4. The search text a client sends is only matched against that list. Selections are made by the opaque option key of a listed row and re-checked by the server, so a crafted search or locale cannot select a record that was not offered.

## Not covered

- **Resource-pack translations of record choices.** Those exist only on the client, so the server cannot match them; choice search covers the bundled languages and literal names. The task catalogue does cover them.
- **Other mods' and vanilla keys on a dedicated server.** A dedicated server has only English for those, so such parts of a label are matched in English.
- **Languages other than the three bundled ones.** A player using another language still searches the catalogue in their language (through their resource packs), and record choices in the bundled languages and literal names.

## Protocol impact

The interaction channel's protocol version is `3`. Client and server must both run this version; a mismatched client is refused at connection, as for any Forge channel version mismatch. Saves, data pack schema and the public API are unchanged by this design.
