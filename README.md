# Vault Hunters 3 Deck Optimiser

A small Forge mod for the Vault Hunters 3rd Edition modpack (Minecraft 1.18.2).
It adds a **Best** button to the Card Binder screen. Pressing it searches for
the best arrangement of your cards in a deck, scored with weights you set per
attribute, and shows the top layouts next to your current one.

- **Client only:** it shows suggestions and nothing else. It sends nothing to
  the server and moves no items.
- **Client and server:** with the same JAR on the server, an **Apply** button
  has the server move the cards into the layout you picked.

Scores come from the Vault mod's own deck code, so scaling cards, conditions,
greed cards and deck modifiers count exactly as they do in the game.

This is an unofficial fan project, not affiliated with or endorsed by the Vault
Hunters team. It contains no Vault Hunters code or assets.

## Installing

1. Download the JAR from the
   [latest release](https://github.com/playaz87/vault-hunters-3-deck-optimiser/releases/latest).
2. With the game closed, put it in the pack instance's `mods/` folder.
3. Optional, for **Apply**: put the same JAR in the server's `mods/` folder and
   restart the server. Back up the world first; Apply moves real items.

If you play on a server you do not run, check that extra client mods are
allowed there.

## Built against

| Thing | Version |
|---|---|
| Minecraft | 1.18.2 |
| Forge | 40.3.11 |
| Vault mod | `the_vault-1.18.2-21.6.1-remastered.6956.jar` |
| Curios (compile only) | `curios-forge-1.18.2-5.0.9.2.jar` |
| Pack | Vault Hunters Third Edition Remastered 3.0.1 |
| Java | 17 |

## Using it

1. Open a Card Binder. Put your deck in its deck slot, or leave the slot empty
   to use the deck you have equipped.
2. Flip through the binder's pages. The server only sends the page on screen,
   so the mod knows only the cards on pages you have looked at since opening
   the binder. Cards in your inventory and in the deck are always included.
3. Press **Best** (under the deck slot).

The panel shows:

- **Buttons** along the top: **Re-run**, **Rescan** (read the decks and cards
  again), **Deck stats** / **Weights** (switch the right-hand side) and
  **Close** (or press Escape).
- **Deck picker**: `<` `>` (or scroll over it) switches which deck is being
  optimized. Your own decks come first: the one in the binder, the one
  equipped, and any in your inventory. Each is followed, in green, by a trial
  of every deck core in your inventory it could take (see
  [Trying a core](#trying-a-core)). After those comes an empty deck of every
  type in the pack's `config/the_vault/card/decks.json`, shown in blue, so you
  can see how your cards would do in a shape you do not own. Previews have no
  cores, and deck types the config marks as unobtainable are left out.
  Cards sitting in your other decks count as available.
- **Layouts by weighted score**: the current deck and up to five suggestions,
  with the score gain and how many slots differ. Click one to view it.
- **Required cards**: cards every suggestion must hold (see
  [Required cards](#required-cards)).
- **The deck grid** for the selected layout. Slots outlined in yellow differ
  from the current deck; a gold corner marks a required card. Hover a card to
  see where it was found; click it to require it (or stop requiring it).
- **Attributes** (the **Weights** view): every attribute your cards can give,
  its weight, its cap, and its total now and in the selected layout. Hover a
  row for the game's own wording. Use `-` / `+` to change a weight (shift-click
  for steps of 1), or click the weight or the cap to type a number (Enter
  saves, Escape cancels), then **Re-run**.
- **Deck stats**: the deck's own tooltip text (stats, card effects, sockets and
  cores) for the current deck beside the selected layout, exactly as the game
  writes it.

## Required cards

Some cards score nothing but you still want them in the deck: resource and
task cards, or the resource cards a Bounty core needs. Mark them as required
and every suggestion keeps them, placed where they cost the least score.

- In the binder (with the panel closed), **Alt+click** a card in the binder or
  your inventory. Required cards get a gold outline. Alt+click again to stop.
- In the panel, click a card in the deck grid.
- Under **Required cards** in the panel, `-` / `+` set how many copies are
  required.

Marking one card marks every identical copy. Required cards are remembered
until the game closes. Press **Re-run** after changing them.

If a required card cannot go in (for example an arcane card with no free
arcane slot), the panel says how many did not fit. A required card you have
not shown the mod (not in a deck, a binder page you looked at, or your
inventory) is listed as not found.

## Caps

Some stats stop helping past a limit. Give an attribute a **cap** and anything
above it scores nothing, so the search stops stacking it and spends the slots
elsewhere. Click the attribute's cap cell and type the largest total that
still counts, in the same units as the totals beside it. Leave it empty for no
cap. A total over its cap is shown in orange with a `*`.

Caps only look at the deck. Stats from your gear are not counted yet, so if
your gear already gives some of a capped stat, set the deck's cap to what is
left.

## Trying a core

A deck with a free socket can take a deck core (a `the_vault:deck_socket`
item). Keep the cores you want to compare in your inventory: the deck picker
then offers each one the deck could take as a green trial, such as
"In binder: Starter Deck + Crimson Core". The trial uses the same rule as the
anvil: a free socket, or a better version of a core already in the deck.

Colour and efficiency cores change card values, so the trial's scores show
what the core would really give. Cores with a condition on the layout, such as
a Bounty core's "at least 12 resource cards", add a **Core Conditions Met**
attribute: raise its weight, or mark the resource cards as required, to make
the search meet it.

A trial is a what-if: **Apply** is off for it. Socket the core at an anvil,
**Rescan**, and optimize the deck itself. Cores that pick their slots at random
when socketed (the deck picker's tooltip says so) used one roll for the trial,
so the real deck may differ.

## Applying a layout

Needs the mod on the server too; otherwise **Apply** is greyed out.

Select a suggestion for the deck in the binder's deck slot and click
**Apply #n**, then click again to confirm. The server then:

1. Checks the binder is still open, the deck has the same shape, every card in
   the layout still exists (in the deck, the binder's storage or your
   inventory), each card fits its slot, and the binder has room for the cards
   coming out. If any check fails it changes nothing and says why.
2. Moves everything in one go. Cards leaving the deck go into the binder.

It cannot rearrange an equipped deck or one in your inventory, and it cannot
take cards out of your other decks; put the deck in the binder's slot first.
It never puts cores into sockets, so a core trial cannot be applied.
After applying, the binder's storage has changed, so flip through its pages
again before the next run.

## How the score works

Attribute totals come from the Vault mod's own deck code, so scalers,
conditions, greed cards and deck cores behave as in the game. Each total is
cut down to its cap if it has one, divided by the largest value any single one
of your cards gives for that attribute, multiplied by its weight, and summed.
So with all weights at 1, one point is roughly "one best card's worth" of any
attribute.

Cards that give no stat themselves (resource and task cards) score nothing by
default. Require them (see [Required cards](#required-cards)), or raise the
weight of **Cards With No Stat** to favour them in general.

Settings are saved in `config/vhdeckoptimizer.json` (`weights`, `caps`,
`defaultWeight`, `iterations`, `restarts`).

## Building

Needs a JDK 17.

```
./gradlew build
```

The JAR lands in `build/libs/`. Copy it into the `mods/` folder of the client
(and of the server, for Apply) while the game is closed.

The Vault and Curios JARs are downloaded from CurseForge (through CurseMaven)
for compiling only; `gradle.properties` holds their project and file ids. They
are never part of this repository or of the built mod.

## Releases

GitHub Actions builds and tests every push. Pushing a tag publishes a release
with the JAR attached, versioned from the tag:

```
git tag v1.0.1
git push origin v1.0.1
```

## When the pack updates

Everything that touches the Vault mod is in
`src/main/java/vhdeckopt/vault/VaultAdapter.java`; its header lists what it
relies on. Update `vault_file_id` in `gradle.properties` to the new file's CurseForge id,
rebuild, and fix that one file if it no longer compiles.

## Source layout

- `search/` – the annealing search (with required cards) and the scoring (with
  caps); plain Java, unit tested.
- `vault/VaultAdapter.java` – the only Vault-facing code.
- `client/` – the binder button, the card cache, the required cards and the panel.

## Licence

MIT. See [LICENSE](LICENSE).
