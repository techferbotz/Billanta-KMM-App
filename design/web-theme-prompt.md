# Billanta web app: design system

You are building the web version of **Billanta**, a GST invoicing app for small businesses in India. The phone app (Kotlin Multiplatform with Compose, on Android and iOS) is the reference design. The web app should look and feel like the same product: same colours, type, shapes and components.

All values below come from the app's source code. Use them exactly, and don't let a UI kit's defaults (its grays, shadows, radii or font sizes) replace them.

Conventions in this document:
- One Compose dp or sp equals one CSS px.
- Paddings are written in CSS order: `9px 16px` means 9px top and bottom, 16px left and right.
- `style / colour` means "in that type style, in that colour", e.g. `caption` / `text-muted`.

## 1. Look and feel

- **Flat and calm.** A cool light-gray canvas with white cards. Cards have a 1px hairline border and no shadow. The only shadow in the whole app is on the floating "+" (new invoice) button.
- **One brand colour, used sparingly.** Indigo `#5B4FE0` is for primary buttons, selected chips and tabs, links, the active nav item and the "+" button. Everything else is neutral.
- **Status is a soft tint.** Coloured text on a pale fill of the same hue: green for Paid, amber for Pending, gray for Draft. Never a saturated block.
- **Generous rounding.** 18px cards, 14px buttons and inputs, fully round chips.
- **Hierarchy from weight and colour.** Bold titles with slightly tight tracking sit over quiet gray secondary text. Sizes stay in a narrow range.
- **Paper stays paper.** Invoices are shown as pictures of the real page, and the page is white in dark mode too.
- **Plain, friendly copy** in sentence case.

## 2. Colour

Token names mirror the app's `BillantaColors`. Light is the default. Dark mode is a user setting (Settings → Appearance → "Dark mode") remembered on the device; the app doesn't follow the OS setting.

```css
:root {
  color-scheme: light;
  --background:     #F4F5F7; /* page canvas, top bars */
  --surface:        #FFFFFF; /* cards, inputs, secondary buttons, tab bar, action bars, sheets, dialogs */
  --surface-alt:    #EFF1F4; /* icon tiles, stepper, neutral tags, image placeholders, switch track (off) */
  --surface-sunken: #FFFFFF; /* defined, not used yet */
  --primary:        #5B4FE0; /* brand: primary buttons, selection, links, active nav, text caret */
  --on-primary:     #FFFFFF; /* text and icons on primary */
  --primary-muted:  #ECEBFB; /* faint indigo wash: empty-state tiles, default avatars, highlight tags */
  --text-primary:   #1C1F2A; /* titles, body text, values */
  --text-secondary: #6B7280; /* supporting text, field labels, subtitles, unselected chip text */
  --text-muted:     #9AA0AD; /* placeholders, chevrons, field icons, inactive nav, metadata, overlines */
  --border:         #E6E8EC; /* every 1px border and divider */
  --border-strong:  #D7DAE0; /* defined, not used yet */
  --success:        #16A34A; /* Paid */
  --success-bg:     #DCFCE7;
  --warning:        #B45309; /* Pending, field warnings, maintenance and not-synced notices */
  --warning-bg:     #FDECC8;
  --draft:          #6B7280; /* Draft */
  --draft-bg:       #EDEFF2;
  --danger:         #DC2626; /* delete, sign out, failures */
  --danger-bg:      #FEE2E2;
  --accent-dot:     #E8850C; /* defined, not used yet: a small "unpaid" dot beside a name */
  --scrim:          rgb(16 17 20 / 0.4); /* behind sheets and dialogs */
  --paper:          #FFFFFF; /* invoice pages, in both themes */
}

:root[data-theme="dark"] {
  color-scheme: dark;
  --background:     #0F1117;
  --surface:        #1A1D26;
  --surface-alt:    #222633;
  --surface-sunken: #13161E;
  --primary:        #7B72F0;
  --on-primary:     #FFFFFF;
  --primary-muted:  #2A2A47;
  --text-primary:   #F2F3F5;
  --text-secondary: #A6ADBB;
  --text-muted:     #767D8C;
  --border:         #2A2E3A;
  --border-strong:  #3A3F4D;
  --success:        #4ADE80;
  --success-bg:     #14321F;
  --warning:        #F0B24B;
  --warning-bg:     #3A2E14;
  --draft:          #A6ADBB;
  --draft-bg:       #262B37;
  --danger:         #F87171;
  --danger-bg:      #3A1D1D;
  --accent-dot:     #F0A03C;
  --scrim:          rgb(0 0 0 / 0.6);
}
```

How to apply colour:
- **Status pills:** Draft is `draft` on `draft-bg`, Pending is `warning` on `warning-bg`, Paid is `success` on `success-bg`. Use the same mapping everywhere.
- **Product kinds:** a service is `primary` on `primary-muted` with a briefcase icon. Goods are `warning` on `warning-bg` with a package icon. Unclassified is `text-secondary` on `surface-alt` with a tag icon.
- **Tinted boxes:** warning banners keep their message in `text-primary` and colour only the icon. Error notes put `danger` text on `danger-bg`.
- **Disabled:** the same colours at 45% opacity (the fill of a primary button; the text and icon of a secondary one).
- **Toasts are inverted:** a `text-primary` background with `background`-coloured text.
- **Over invoice pictures:** white text, with secondary lines at 85% opacity, on a dark gradient (see Invoice card).

### Avatar colours

Customer cards colour each customer from their **id**, not their position in the list, so a customer is the same colour on every screen and every visit. There are six pairs, each with a dark-mode version:

| Hue | Light bg | Light fg | Dark bg | Dark fg |
|---|---|---|---|---|
| Indigo | `#ECEBFB` | `#4B3FD0` | `#2D2A5C` | `#B9B3F7` |
| Teal | `#E1F5EE` | `#0F6E56` | `#123D33` | `#8FD9C0` |
| Coral | `#FAECE7` | `#993C1D` | `#4A2419` | `#F2B39B` |
| Amber | `#FAEEDA` | `#854F0B` | `#453012` | `#F3C77E` |
| Pink | `#FBEAF0` | `#993556` | `#4A2231` | `#F0A9C0` |
| Blue | `#E6F1FB` | `#185FA5` | `#16304A` | `#9CC7F1` |

Pick the row with the app's own formula (Kotlin's `String.hashCode`), so a customer gets the same colour on the web as on Android:

```js
// Index into the table above, top to bottom.
function avatarHue(customerId) {
  let h = 0;
  for (let i = 0; i < customerId.length; i++) {
    h = (Math.imul(31, h) + customerId.charCodeAt(i)) | 0;
  }
  return (h & 0x7fffffff) % 6;
}
```

Every other avatar (the customer picker, the signed-in user) is `primary` on `primary-muted`.

### Invoice colour presets

Only needed if the web app lets people recolour an invoice. The app offers the template's own colour first, then these presets, five to a row and fifteen at most:

`#5B4FE0` `#3B33B5` `#2B3648` `#0F172A` `#475569` `#0F766E` `#047857` `#15803D` `#4D7C0F` `#B45309` `#C2410C` `#B91C1C` `#BE123C` `#9333EA` `#7E22CE`

## 3. Typography

The app uses each platform's default sans (Roboto on Android, SF Pro on iOS), which renders close to the design's Inter look. On the web, use Inter at weights 400, 500, 600 and 700:

```css
font-family: "Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
```

| Style | Size / line height | Weight | Tracking | Used for |
|---|---|---|---|---|
| `screen-title` | 28 / 34 | 700 | −0.5px | Titles of the four main tabs ("Invoices"), the account name |
| `section-title` | 20 / 26 | 700 | −0.3px | Titles of pushed screens, dialogs and sheets; empty-state titles |
| `card-title` | 16 / 22 | 600 | 0 | Customer names, card headings |
| `body` | 15 / 21 | 400 | 0 | Paragraphs, input text, descriptions |
| `body-strong` | 15 / 21 | 600 | 0 | Buttons, list-row titles, product and template names |
| `label` | 13 / 17 | 500 | 0 | Field labels, chips, month headers, avatar initials (at 600) |
| `caption` | 12 / 16 | 400 | 0 | Subtitles, help text, metadata, card details |
| `overline` | 11 / 14 | 600 | +0.8px | Group headers (uppercase, `text-muted`); status pills (not uppercase) |
| `amount` | 16 / 20 | 700 | 0 | Prices on product cards |
| `amount-large` | 22 / 26 | 700 | −0.3px | Summary totals (defined, not on a screen yet) |
| `money-hero` | 34 / 40 | 700 | −0.6px | A hero total (defined, not on a screen yet; drawn 2% condensed) |

A few one-off sizes: bottom-nav labels are 11px/400, tags are 11px/500 and the Premium badge is 10px/600. Give amounts `font-variant-numeric: tabular-nums`; the design uses tabular figures for money.

## 4. Shape

| Radius | Used for |
|---|---|
| 18px | Cards, grouped settings lists, customer and product cards; the 56px icon tile in problem boxes |
| 16px | Invoice cards and template cards |
| 14px | Buttons, inputs, the search bar, picker fields, toasts, notice and problem boxes |
| 12px | Pressed/hover area of list rows and nav items, error notes, the 72px image tile, small template choices |
| 11px | 40px icon tiles |
| 10px | The quantity stepper, 44px product thumbnails, option rows in pickers |
| 8px | Text-button hit areas, underline-tab hit areas, dashed "Add …" boxes on the invoice preview |
| 6px | Invoice page previews |
| 999px | Chips, tags, status pills, the Premium badge, tick badges |
| Circle | Avatars, the "+" button, top-bar icon buttons, colour swatches |
| 22px | The 76px empty-state tile; custom dialogs |
| 20px | The 64px tile at the top of sheets |
| 26px | Top corners of bottom sheets |

## 5. Spacing and layout

- **18px side padding on every screen:** top bars, content, sticky action bars, toasts and banners.
- **Gaps:** invoice and product grids 12px both ways; the template grid 14px across and 16px down; stacked cards 10px; settings groups 18px.
- **Card padding:** 16px by default. Customer cards use 14px, a product card's body 12px (its image runs to the edges), and grouped list cards 4px, with the rows supplying their own padding.
- **Control heights:** 52px minimum for buttons, inputs and the search bar; 56px for picker fields.
- **Labels and help:** a field label sits 6px above its input; help or warning text 4px below it.
- **Group headers:** an overline sits 8px above its card.
- **Tab bar clearance:** screens with the bottom tab bar leave 96px of empty space at the end of their scrolling content.

## 6. Depth and lines

- **No shadows.** Separation comes from `surface` on `background` and 1px `border` lines. The one exception is the "+" button (Material elevation 10, roughly `0 6px 16px rgb(16 17 20 / 0.2)` in CSS).
- **Bars have no edge.** The bottom tab bar and sticky action bars are plain `surface`, with no top border or shadow.
- **Dividers** are 1px `border`. In grouped lists they're inset 16px on the leading side.
- **Selection** is a 2px `primary` outline (template cards); colour swatches use 3px.

## 7. Icons

The app draws its own line icons on a 24px grid: 1.9 stroke, round caps and joins, no fills. On the web, use **Lucide** with `strokeWidth={1.9}`; it's the closest match.

| App | Lucide | App | Lucide | App | Lucide |
|---|---|---|---|---|---|
| Receipt (Invoices tab) | `receipt` | Catalogue (tab) | `notebook-text` | Grid (Templates tab) | `layout-grid` |
| Person (Profile tab) | `user` | People | `users` | Plus | `plus` |
| Search | `search` | Tune | `settings-2` | Gear | `settings` |
| ArrowLeft | `arrow-left` | ChevronRight | `chevron-right` | ChevronDown | `chevron-down` |
| Check | `check` | Close | `x` | Share | `share` |
| Download | `download` | Pencil | `pencil` | Trash | `trash-2` |
| Lock | `lock` | CloudOff | `cloud-off` | Star | `star` |
| Camera | `camera` | Info | `info` | Plane (support) | `send` |
| Briefcase | `briefcase` | Package | `package` | PriceTag | `tag` |
| MapPin | `map-pin` | Phone | `phone` | Calendar | `calendar` |
| Bell | `bell` | Copy | `copy` | Qr | `qr-code` |
| Sun | `sun` | Moon | `moon` | | |

- **Sizes:** 20px in buttons, fields and list rows; 23px in the bottom nav; 24px in top bars; 26px on the "+" button; 34px in empty-state tiles; 14px beside detail lines (location, phone).
- **Colour follows context:** `text-muted` for chevrons, field icons and inactive nav; `text-secondary` inside icon tiles; `primary` for the active tab and add actions; `danger` for delete.

## 8. Components

### Buttons

- **Primary:** `primary` fill, `on-primary` text in `body-strong`, radius 14, min height 52px, padding `0 20px`. An optional 20px leading icon sits 8px from the text. Disabled: the fill at 45%.
- **Destructive:** the primary button with a `danger` fill (used to confirm account deletion).
- **Secondary:** `surface` fill, 1px `border`, `text-primary` text, same size and padding. "Sign out" is a secondary button with `danger` text and icon.
- **Text button:** `body-strong` in `primary` (or `danger` for a destructive choice), padding `4px 6px`, radius 8, no background. Dialog actions are always text buttons.
- **Pairs:** two buttons share a row equally with a 12px gap, for example "Share PDF" (primary) beside "Edit" (secondary), or "Cancel" beside "Done". In sheets and cards, buttons are full width.
- **Continue with Google:** a primary button with the multicolour Google "G" (20px, in its own colours) as the leading icon.

### Inputs

- **Text field:** `surface` fill, 1px `border`, radius 14, min height 52px, padding `0 14px`. Text in `body` / `text-primary`, placeholder in `text-muted`, caret in `primary`. An optional 20px `text-muted` leading icon sits 10px from the text. The label goes above in `label` / `text-secondary`.
- **Picker field** (opens a list or date picker): the same box at 56px, showing the value in `body` (placeholder in `text-muted`) and a 20px `text-muted` chevron at the end: down for a select, right for a picker screen.
- **Search bar:** a 52px text field with a search icon and the placeholder "Search by number or customer". It's the first item of the invoice grid, full width, and scrolls with it.
- **Help and validation:** help text is `caption` / `text-secondary`. Validation is soft: a format problem replaces the help line with a warning in `warning`. There are no red borders and nothing blocks typing.
- **Select:** with up to four options, a row of filter chips. With more, a picker field that opens a dialog list (rows in `body`, padding `12px 8px`, radius 10, an 18px `primary` check on the current one; actions "Clear" and "Cancel").
- **Switch** (Material 3, 52×32 track): on is a `primary` track with a 24px white thumb; off is a `surface-alt` track with a 2px `border` outline and a 16px `text-muted` thumb. As a form field it sits in a bordered, 14px-radius box with its label in `body`.
- **Image field:** a 72px `surface-alt` tile (radius 12, 1px `border`) with a 26px `text-muted` camera icon until there's an image, then a secondary button ("Add logo" or "Change") and a `danger` text button "Remove".
- **Quantity stepper:** a `surface-alt` box with radius 10. The "−" and "+" are 38px buttons with 20px bold `primary` glyphs; the value between them is `body-strong`, at least 40px wide.

### Chips, tags and badges

- **Filter chip:** pill, padding `9px 16px`, `label` text. Selected: `primary` fill, `on-primary` text. Unselected: `surface` with a 1px `border` and `text-secondary` text. Chips sit 8px apart in a row that scrolls sideways.
- **Tag:** pill, padding `2px 8px`, 11px/500 text on a tint. Examples: "GST registered" (`primary` on `primary-muted`), "18% GST" (`text-secondary` on `surface-alt`), "Service" or "Goods" over a photo (`text-primary` on `surface` at 92%).
- **Status pill:** pill, padding `2px 7px`, `overline` style, coloured by status.
- **Premium badge:** pill with a `primary` fill, padding `3px 8px`: a 12px star and "Premium" in 10px/600 `on-primary`, 3px apart.
- **Tick badge** on the selected template: a 26px `primary` circle with a 16px white check.
- **Premium pill** on the profile: `caption` at 600 in `primary` on `primary-muted`, padding `5px 12px`.

### Cards and lists

- **Card:** `surface`, 1px `border`, radius 18, 16px padding. When a card opens something, the whole card is the click target.
- **Grouped list** (Profile, Settings): an uppercase `overline` header in `text-muted`, 8px above a card with 4px padding that holds rows separated by inset dividers.
- **List row:** 12px vertical padding and 14px gaps. Leading: an **icon tile** (40px, radius 11, `surface-alt`, 20px `text-secondary` icon; a destructive row uses `danger` on `danger-bg`). Then a `body-strong` title (`danger` when destructive) with an optional `caption` / `text-secondary` subtitle 2px below. Trailing: an optional value in `label` / `text-secondary` and a 20px `text-muted` chevron.
- **Avatar:** a circle with initials in `label` at 600. 32px in a picker field, 44px in lists, 46px on customer cards, 96px in the profile header.
- **Customer card** (no padding of its own):
  - Top block, 14px padding, 12px gap: a 46px avatar beside a column with 3px gaps. The column holds the name in `card-title` (with a "GST registered" tag when there's a GSTIN), "GSTIN …" in `caption` / `text-secondary`, and detail lines: a 14px `text-muted` icon plus `caption` / `text-secondary` text, such as "Pune, Maharashtra · same state" and "98… · name@mail.com".
  - A 1px divider.
  - Three equal stat columns with 8px vertical padding: a value in `label` at 600, `text-primary`, over a `caption` / `text-muted` label ("12 invoices", "₹4.56 L billed", "12 Sep last invoice"). With no invoices, the row is "No invoices yet", centred, in `caption` / `text-muted`.
- **Product card** (grid, 150px minimum width; no padding of its own):
  - A 4:3 image on top: the photo, cropped to fill, or the product kind's tinted tile with a 34px icon.
  - A body with 12px padding and 4px gaps: the name in `body-strong` (always two lines tall); the price in `amount` followed by " / unit" in `caption` / `text-secondary`; an "18% GST" tag beside "HSN 1234" or "SAC 9983" in `caption`; and a usage line in `caption` / `text-muted` ("Used 3 times · last 12 Sep").
- **Invoice card** (the invoice list, two columns on a phone):
  - A picture of page 1 at full card width, aligned to the top, showing the top 60% of the page: `aspect-ratio: 595 / 505.2`, radius 16. The fill is `surface-alt` until the picture arrives, and the 1px `border` is drawn on top of the picture.
  - A gradient over the picture, from transparent at 30% down to `rgb(0 0 0 / 0.7)` at the bottom, carries white text at the bottom left with padding `9px 10px`: the number (`label` at 600) with the status pill at the right; the customer (`caption`, white at 85%); then the amount (`label` at 700) and the date (`caption`, white at 85%), 6px apart.
  - If the invoice failed to sync, a 26px `danger` circle with a white cloud-off icon sits 8px in from the top-right corner.
- **Month header** above each month's invoices: "September 2026" on the left and the month's total on the right, both in `label` / `text-secondary`.
- **Template card:**
  - A white A4 page (`aspect-ratio: 595 / 842`), radius 16, 1px `border`. When selected: a 2px `primary` border plus the tick badge at the bottom right. Premium templates carry the Premium badge 6px in from the top right.
  - Below, after a 10px gap and inset 4px at the sides: the name in `body-strong` (up to two lines) with "Premium" (`caption`, `primary`) or "Free" (`caption`, `text-muted`) on the right, then the category in `caption` / `text-secondary`.
  - While a template's picture loads, the page shows a simple sketch: `primary` bars for the business name, a rule and the total, and `#E6E8EC` bars for the text lines. Premium templates use a dark page (`#17171C`) with `#3A3A45` lines and white accents.
- **Section row** (invoice editor): a card with padding `14px 16px` and 12px gaps. A 10px dot (`success` when the section has content, `border` when empty), then the section label in `body-strong` over up to six detail lines in `body` / `text-secondary` ("+3 more" in `caption` / `text-muted` beyond that), then a switch or an 18px chevron.

### Navigation

- **Large top bar** (the four main tabs): `background` colour, the title in `screen-title`, padding `8px 9px 10px 18px`, actions at the right.
- **Stack top bar** (pushed screens): padding `6px 9px 6px 3px`. A back-arrow icon button, then the title in `section-title` on one line, then actions.
- **Icon button:** a 24px icon with 9px of padding, a 42px round hit area. Neighbouring actions sit 2px apart. A colour swatch in the bar uses the same box: a 24px circle with a 1.5px `border` ring.
- **Bottom tab bar** (phone): `surface`, 10px vertical padding, five equal slots: Invoices, Catalogue, an empty slot under the "+" button, Templates, Profile. Each tab is a 23px icon over an 11px label, 3px apart, in `primary` when active and `text-muted` when not.
- **"+" button:** a 52px `primary` disc inside a 3px `surface` ring (58px overall) with a 26px white plus. Its top edge sits 22px above the tab bar's, so it overlaps the bar, and it carries the app's only shadow.
- **Underline tabs** (Catalogue's Customers / Products): equal-width labels in `body`, 600 and `text-primary` when active, `text-secondary` otherwise, with 12px vertical padding. Under them runs a 2px `border` track with a 2px `primary` indicator one tab wide that slides as you swipe.
- **Sticky action bar** (bottom of pushed screens): `surface`, padding `12px 18px`, kept above the on-screen keyboard. It holds a pair of buttons, or a status caption over one full-width button.

### Overlays and feedback

- **Dialog:** a Material 3 alert on `surface` (28px radius, 24px padding). Title in `section-title`, message in `body` / `text-secondary`, text buttons at the bottom right: "Cancel" in `primary`, a destructive confirm in `danger`. The app's custom dialog (the colour picker) uses radius 22 and 20px padding.
- **Bottom sheet:** `surface`, 26px top corners, `scrim` behind, and a 32×4px `text-secondary` drag handle with 22px above and below it. Content has 18px padding, is centred and spaced 12px apart: a 64px tile (radius 20, `primary-muted`, 30px `primary` icon), a `section-title`, a `body` / `text-secondary` paragraph, feature lines (a 22px `success-bg` circle with a 15px `success` check, then `body` text), then a full-width primary button above a full-width secondary one.
- **Toast:** at the bottom, 96px up, with 18px side margins. Radius 14, padding `12px 16px`, inverted colours (`text-primary` fill, `background`-coloured `body` text). It goes away after 4 seconds or when tapped.
- **Banner** (maintenance): full width at the very top, `warning-bg`, padding `10px 18px`, a 20px `warning` cloud-off icon and `caption` text in `text-primary`.
- **Notice box** (an invoice that won't sync): `warning-bg`, radius 14, 14px padding. An icon and `caption` text, then a text button ("Use the next invoice number").
- **Error note** (e.g. sign-in failed): `caption` in `danger` on `danger-bg`, radius 12, padding `10px 12px`.
- **Empty state:** centred, 60px from the top. A 76px tile (radius 22, `primary-muted`, 34px `primary` icon); 18px; a `section-title`; 6px; centred `body` / `text-secondary`; 20px; a primary button with a plus icon. For example "No invoices yet" / "Create your first invoice in under 30 seconds." / "New invoice".
- **Problem box** (a template that failed to load): `surface`, 1px `border`, radius 14, 24px padding, centred: a 56px tile (radius 18, `primary-muted`, 26px icon), the message, and a secondary "Retry" button.
- **Loading:** skeletons shaped like the real cards (`surface-alt` with a 1px `border`, bars in `border` colour, radius 5) pulsing between 45% and 90% opacity over 750ms. Elsewhere, a centred note in `body` / `text-muted` ("Loading template…").
- **Saving:** the invoice editor has no Save button. Changes save themselves about 700ms after the last edit. The sticky bar shows "Changes are saved as you make them", "Saving…" or "Saved" in `caption` / `text-muted` (or the error in `danger`) above a full-width primary "Done".
- **Invoice preview:** each page on white paper, full width, radius 6, 1px `border`. An empty section shows a dashed 1.5px `primary` box (short dashes, radius 8) with a centred `caption` in `primary`, such as "Add Bank details". Multi-page invoices show "Page 1 of 2" in `caption` / `text-muted` under each page.

## 9. Motion

- Screens crossfade over 240ms; they don't slide.
- Invoice and template pictures fade in over 220ms.
- Skeletons pulse between 45% and 90% opacity, 750ms each way, forever.
- The app's press feedback is the Material ripple; see section 11 for the web.
- Nothing bounces or springs.

## 10. Content and formatting

- **Money:** `₹` with Indian digit grouping, e.g. `₹1,08,560.00`. Lists drop zero paise (`₹2,36,000`) but keep real ones (`₹18,880.50`). Narrow spots use the compact form: `₹37,760`, `₹4.56 L`, `₹1.2 Cr`. Other currencies show the code and a space: `USD 1,500`.
- **Dates:** "12 Sep" on cards and "September 2026" for month headings.
- **Separators:** " · " between pieces of metadata.
- **Progress:** in-progress labels end in a real ellipsis character: "Saving…", "Signing in…", "Uploading…".
- **Tone:** sentence case everywhere. Titles are short nouns ("Invoices", "Edit invoice", "Choose a template"). Empty states say what to do next.

## 11. Adapting to wider screens

The app only runs on phones, so these are suggestions for the web, not features to copy. Build them from the same tokens.

- **Below 768px,** mirror the phone layout: bottom tab bar with the raised "+", large titles, sticky bottom action bar.
- **Navigation on wider screens:** move the four tabs into a left sidebar on `surface` with a 1px `border` edge. The active item gets a `primary` icon and text on `primary-muted`, with radius 12. The "+" becomes a primary "New invoice" button.
- **Content width:** cap the content column at about 1200px, with 24–32px side padding.
- **Grids:** auto-fill with a 150px minimum card width (the app's own adaptive minimum), so wide screens get more columns rather than wider cards.
- **Sheets:** bottom sheets can become centred dialogs (radius 22, about 440px wide).
- **Hover and focus** (the app has neither): rows and ghost controls get a `surface-alt` background on hover; clickable cards switch their border to `border-strong`; primary buttons darken slightly. Show keyboard focus as a 2px `primary` outline with a 2px offset.
- **UI kits:** if the project uses Tailwind or a component kit, map these tokens into its theme rather than using its default palette, shadows or radii.

## 12. Don'ts

- Don't add shadows to cards, bars, sheets or dialogs. Only the "+" button has one.
- Don't show validation as red borders. Format problems are amber help text; `danger` is for destructive actions and real failures.
- Don't use pure black text or a pure white canvas: text is `#1C1F2A` and the canvas is `#F4F5F7`.
- Don't uppercase anything except overline group headers.
- Don't invent new accent colours, and don't tint invoice pages in dark mode.

## 13. Assets

These are in the app repository's `design/` folder; copy them into the web project.
- `app-icon.svg`: the logo and favicon (a white mark on brand purple `#5B4FE0`).
- `google-g.svg`: the Google mark for "Continue with Google".
