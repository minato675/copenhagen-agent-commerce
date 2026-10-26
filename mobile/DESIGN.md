# DESIGN.md — A Payment App That Feels Alive

> Make money feel understandable, tangible, and effortless. Bring the visual confidence of Revolut to this project's own identity, then make every interaction feel exceptionally connected and considered.

**Status:** Prescriptive redesign contract, not a completed audit or implementation report.\
**Scope:** The existing payment application and its supported journeys.\
**Document language:** English. Preserve the application's existing user-facing language and locale.\
**Reference review date:** 2026-09-21.

This document governs visual design, interaction design, original assets, motion, perceived performance, implementation, and verification. Requirements use **must**; tunable starting points use **target**. All palettes, dimensions, timing values, spring values, and choreographies below are original project specifications, not claimed Revolut internals.

---

## 1. North star and decision hierarchy

The desired reaction is:

> “This feels like something the Revolut team could have built, but the details are even more alive.”

The experience should communicate three things without a tutorial: **what I have, what I can do, and what just happened.**

Priority order:

1. Correct financial state, security, accessibility, and user control.
2. Immediate understanding and the shortest safe path to the user's goal.
3. Continuous, beautiful, purposeful motion.
4. Decorative richness.

Within those boundaries, prefer a smooth, complete transition over an abruptly faster-looking cut. Do not sacrifice the animation's quality merely because an API returned quickly. Equally, do not turn animation into a mandatory waiting room.

### The six experience commitments

| Commitment | Required interpretation |
| --- | --- |
| Minimal reading | Communicate through hierarchy, recognizable objects, placement, amounts, and direct manipulation. Keep short labels wherever ambiguity would remain. |
| No training required | Make the next action evident. No essential hidden gestures, unexplained symbols, or mandatory feature tours. |
| Beauty with a job | Motion must explain origin, destination, selection, progress, consequence, or recovery. Important moments receive distinctive choreography. |
| No unnecessary inconvenience | Preserve input, context, and position. Remove redundant steps and unjustified permissions. Keep necessary financial review and authentication. |
| No perceived loading in normal use | Start useful presentation immediately; overlap genuine work with meaningful transitions. Escalate honestly when work outlasts the transition. |
| A living interface | Every interactive component and meaningful state change has a deliberate motion treatment. Resting content stays calm; “alive” does not mean “always moving.” |

**The interface should remain understandable with explanatory prose covered, animation disabled, sound muted, and color removed.** Treat this as a design test, not proof that every person can use an entirely text-free financial product.

---

## 2. Respect the existing product

Before redesigning, inspect the repository and run the application where possible. Discover rather than assume:

- Platform, framework, routing, design conventions, installed animation tools, build commands, and test commands.
- Existing screens, transaction types, authentication, permissions, API contracts, money representation, caching, and error handling.
- Current brand, locale, supported devices, accessibility support, and real versus mocked features.

Create a compact inventory with: `journey → current route → user goal → friction → proposed change → preserved dependency → required states`.

Use the product's actual main task. A merchant checkout app, peer-to-peer wallet, and QR payment app do not need identical navigation. The examples below are adaptable patterns, not permission to invent banking features.

Preserve working business logic, authentication, integrations, existing data, and unrelated changes. Do not replace a functioning app with a disconnected visual prototype. Do not add investing, cryptocurrency, rewards, lending, cards, or account exchange simply because Revolut has them. Do not migrate frameworks to make the redesign easier.

When a capability is absent, omit the corresponding production affordance. A development-only demonstration may use clearly identified fixtures, isolated from real transaction paths. Missing backend capabilities must remain documented limitations, never simulated production success.

---

## 3. Benchmark translation

Revolut's official Revolut 10 introduction describes a simplified home, account switching, personalization, and easier access to money tasks. Its published imagery provides account and background references. [R1] A designer's public **Revolut Home Appearance** showcase explicitly presents a smooth transition from authentication into the home screen. [R2]

These are public references, including historical material—not a verified inventory of every current, authenticated Revolut screen. Preserve that distinction when making implementation decisions.

| Reference quality | Apply to this project | Avoid |
| --- | --- | --- |
| Money-first hierarchy | A dominant balance or task amount, a compact account selector, and immediate access to the main action. | A dashboard full of equally weighted widgets. |
| Visually distinct account surfaces | Controlled color and material cues that help identify context. | Background art that reduces amount readability. |
| Economical controls | Generous touch targets, short labels, recognizable icons, disciplined spacing. | Removing labels until the interface becomes a guessing game. |
| Smooth home appearance | Carry a consistent background and spatial identity across entry and navigation. | Replaying a promotional reveal on every visit. |
| High craft | Refine proportion, typography, interruption behavior, state transitions, and edge cases. | Copying only rounded cards and blue gradients. |

Benchmark closely at the level of composition, restraint, hierarchy, and interaction quality. Keep this project's own name and identity. This is an application redesign, not a Revolut marketing landing page.

---

## 4. Visual system

### 4.1 Art direction: porcelain, graphite, electric depth

Use clean neutral surfaces, confident typography, restrained cobalt accents, and one carefully composed material moment. The financial information is the hero; the material supports it.

Default composition: a light, quiet application canvas; a graphite or deep-cobalt balance surface when it improves hierarchy; crisp action controls; flat, well-spaced activity rows. Support an equivalent dark palette where the app already supports themes. Do not build two incomplete themes instead of one finished core experience.

An original SVG may give the balance or card surface a subtle curved highlight, layered plane, or iridescent edge. Keep text on a contrast-safe region. Avoid decorative spheres floating behind every section, generic purple gradients, pervasive glassmorphism, giant shadows, and excessive dashboard cards.

One expressive material surface per screen is usually enough. Transaction review, errors, and authentication should be especially calm.

### 4.2 Semantic color tokens

Starting palette; validate actual combinations, overlays, and intermediate animation frames before acceptance.

| Token | Light | Dark | Role |
| --- | --- | --- | --- |
| `canvas` | `#F5F6F8` | `#0B0C0F` | Application background |
| `surface` | `#FFFFFF` | `#15171C` | Main content surface |
| `surface-subtle` | `#ECEFF3` | `#20232A` | Quiet grouping and selected wells |
| `text-primary` | `#111318` | `#F7F8FA` | Amounts and primary labels |
| `text-secondary` | `#5E6572` | `#B2B9C6` | Supporting information |
| `border-subtle` | `#DFE3EA` | `#303541` | Decorative separators only |
| `control-boundary` | `#7A8291` | `#7F899B` | Boundaries needed to recognize controls |
| `action-primary` | `#111318` | `#F7F8FA` | Main call-to-action surface |
| `on-action-primary` | `#FFFFFF` | `#111318` | Main call-to-action content |
| `accent` | `#2457E6` | `#8DAAFF` | Selection and controlled emphasis |
| `on-accent` | `#FFFFFF` | `#0B0C0F` | Content on solid accent |
| `positive` | `#087A55` | `#52D5A0` | Confirmed positive state |
| `warning` | `#996000` | `#F4C166` | Attention and delayed state |
| `negative` | `#C43345` | `#FF8694` | Error and destructive action |
| `focus` | `#2457E6` | `#ACC0FF` | Keyboard focus |

Do not use color as the sole indicator of money direction, selection, success, or failure. Pair it with shape, icon, position, and a concise label. A light decorative separator is not an acceptable substitute for a necessary control boundary.

### 4.3 Type and money

Use the existing suitable licensed font or a platform system sans-serif. Do not download proprietary benchmark fonts. Favor excellent numeral shapes and tabular figures. Use relative sizing on the web and platform text scaling on native applications.

| Role | Starting size / line height | Weight |
| --- | --- | --- |
| Primary balance | 48–64 / 1.05–1.12 | 600–700 |
| Amount entry | 48–64 / 1.10 | 600 |
| Screen title | 28–32 / 1.15 | 600–700 |
| Section title | 20–22 / 1.25 | 600 |
| Body and action label | 16 / 1.40–1.50 | 400–600 |
| Supporting label | 14 / 1.35–1.45 | 400–500 |
| Noncritical metadata | 12–13 / 1.35 | 400–500 |

Do not shrink essential information into metadata size. Let long names wrap when necessary. Do not truncate the recipient identity needed to distinguish two people.

Show real amounts without clipping, abbreviated settlement values, invented intermediate balances, or slot-machine counting. Preserve exact underlying values and existing decimal-safe arithmetic. Format with the active locale and currency metadata; do not assume all currencies have two fractional digits. Keep currency unambiguous throughout review and confirmation.

### 4.4 Geometry and layout

Use a 4-unit spacing grid: `4, 8, 12, 16, 20, 24, 32, 40, 48, 64`. Typical mobile gutters are 20; use 16 on narrow screens and 24 where space permits.

Target primary controls at 56 units high and touch hit areas at least 48 × 48 CSS pixels or equivalent platform logical units. This is a project target, not a claim that WCAG mandates 48 pixels.

Starting radii: 12 for small controls, 20–24 for grouped surfaces, 28–32 for sheets, and fully rounded primary pills. Use larger radii selectively, not on every row.

Treat elevation as a hierarchy: `canvas → content → sheet → dialog`. Prefer separation through tone and spacing before adding shadows. Keep shadows stable during motion where possible.

Design mobile-first, then adapt to the actual supported platforms. On wider layouts, use meaningful list/detail composition instead of stretching a phone screen. Respect safe areas, browser chrome, keyboard insets, large text, and landscape. Never hard-code the entire interface to a 390 × 844 viewport or add a decorative phone frame to the application.

---

## 5. Information architecture and friction budget

Use three to five stable primary destinations, based on the inventory. An appropriate wallet default is `Home / Pay / Activity`, with `Cards` only when cards exist. Put profile and infrequent settings behind a clear profile entry. A bottom destination is navigation, not a disguised immediate money-transfer action.

Keep the main payment entry reachable in one tap from home. Show at most three or four prominent home shortcuts. Do not hide the product's main task under “More.” Preserve tab scroll positions and drafts where safe.

Choose one visually dominant action per decision surface. Secondary actions remain visible but quieter. Do not force users to open a second menu to perform the obvious next step.

| Potential friction | Default decision |
| --- | --- |
| Introductory carousel or explanatory tour | Remove; teach through the interaction itself. |
| Re-entering already available information | Reuse permitted, current data. |
| Requesting contacts or camera access on launch | Ask at the moment the related task needs it. |
| Review before irreversible payment | Keep one clear, editable review surface. |
| Existing required authentication | Preserve and integrate with the platform flow. |
| Confirmation for every reversible preference | Remove; provide recovery where appropriate. |
| Mandatory swipe or long press to pay | Do not introduce by default; provide a conventional accessible action. |
| Animation that blocks unrelated navigation | Remove the blocking behavior, not necessarily the animation. |
| Failure that discards input | Preserve the draft and expose the shortest valid recovery. |

Every additional step must have an explicit user benefit, security reason, or real system requirement. “Looks impressive” is not sufficient.

---

## 6. Screen and journey blueprints

Apply only to journeys the project actually supports. Redesign all reachable in-scope states, not only the home screen.

### 6.1 Home: understand, then act

Arrange the first viewport in this order:

1. Compact identity/context controls.
2. Dominant available balance or the product's equivalent primary money state.
3. Account or currency selector, only when meaningful.
4. A small set of direct, labeled actions.
5. Recent activity, with a clear route to the complete history.

Reserve optional secondary modules for below the main tasks. Avoid promotional carousels above payments. Avoid nested cards containing more cards.

Keep the account identity and balance visually linked during switching. Reveal real changes with a short crossfade or bounded vertical transition; never animate through nonexistent financial values. If a balance is stale, unavailable, or estimated, distinguish that state without making the screen visually collapse.

### 6.2 Pay or send: recognize the destination

For transfers, make recent recipients and recipient search immediately accessible. Pair each avatar with a name and enough secondary identity to distinguish duplicates. An avatar alone is not sufficient.

For QR-centric payments, prioritize a working scanner with a clear reticle, manual-entry alternative, and understandable permission-denied recovery. Keep camera preview motion separate from the app's authored overlays. Acquiring a code is not permission to execute a payment.

Carry the selected recipient or merchant visually into amount entry and review. Do not replace them with a generic transfer icon once selected. A subtle shared-element transition should communicate: **this is the same person or business.**

### 6.3 Amount entry: one task, no distraction

Make the amount the dominant element, with the recipient directly above or adjacent. Keep the source account, currency, and available funds easy to inspect.

Use the platform's appropriate input behavior. A custom keypad is acceptable only when it preserves keyboard access, assistive technology, editing, paste where relevant, locale handling, and reliable input. Each digit must update immediately; animation must never queue keystrokes or delay the value.

Animate only newly changed glyphs, subtly, while keeping the actual text and accessible value authoritative. Retain decimal places, signs, and currency positioning correctly. Do not animate the whole amount on every keypress.

Keep the primary action within comfortable reach without covering input. Show validation beside the amount. An unavailable action needs an understandable reason; do not rely on unexplained low opacity.

### 6.4 Review: minimal but complete

Present one readable object containing recipient identity, source, amount, currency, fees, total debit, recipient amount where relevant, and meaningful timing information. Include applicable exchange details when the existing product requires them.

Make editable fields directly editable. Avoid bouncing back through multiple screens to change the amount. Use a specific final action such as `Send ₩25,000`, localized for the product, rather than an ambiguous `Continue`.

Keep required transaction information visible before commitment. Minimal copy is not a reason to hide a fee or disguise the consequence of a button. A clear review/correction mechanism supports error prevention for consequential submissions. [R5]

### 6.5 Authorization and processing: keep the task intact

Use existing platform and provider authorization surfaces. Do not replace secure authentication with a decorative imitation. Do not initiate a payment on selection, hover, animation start, or review-screen entry.

After explicit confirmation and any required authorization, preserve the recipient, amount, and transaction identity while the action surface changes into a compact progress presentation. The user should recognize their task, not encounter an unrelated full-screen loader.

Only the duplicate submission action is locked. Keep appropriate navigation, transaction visibility, and supported recovery available. Closing a sheet after submission must not imply cancellation.

### 6.6 Result and receipt: a consequence, not a cutscene

For a confirmed result, show the exact amount, recipient, status, and useful receipt details. Use a hand-authored check or equivalent status illustration. Let the processing surface become the receipt through spatial continuity.

Make the next useful action available without waiting for decoration to finish. A saved receipt remains reachable from activity. Announce the outcome once to assistive technology.

Use `Pending`, `Scheduled`, `Authorized`, or another accurate status when the backend has not confirmed completion. Never substitute a celebratory success screen for mere request acceptance.

Failure stays calm: one understandable reason, preserved context, and one leading recovery action. Show `No money was sent` only when that is actually known. An ambiguous network outcome requires reconciliation, not an invented failure.

### 6.7 Activity and detail: answer “what happened?”

Prefer a clean list with recognizable identity, time, signed amount, and status. Keep amount alignment stable. Separate pending items clearly without making them disappear from history.

Let tapping a row open a detail surface related to that row. On return, restore the same position. Do not animate every list item again after an ordinary back navigation. Never use invented trends, charts, or transaction data to make the screen look populated.

### 6.8 Cards, requests, top-ups, and secondary flows

When present, use the same visual and motion grammar for these journeys. A selected card can move from a stack into its detail view; freeze/unfreeze must show the actual control state and a pending state until confirmed.

Generate a functional payment QR from actual supported data, and render it sharply with a proper quiet zone. Do not animate, distort, or decorate the machine-readable modules while scanning is expected. Animate the surrounding container instead.

Permissions, empty states, help, settings, and recoverable errors must receive the same care as the primary journey. No orphaned legacy modal should break the system's visual language.

---

## 7. Copy and recognition

**Delete explanation before deleting meaning.**

Buttons usually use one to three words; contextual amount-bearing actions may be longer. Prefer verbs and familiar nouns. Each state should usually need no more than one short explanatory sentence, but accuracy, localization, and safety take precedence over a word budget.

**User-directed copy update, 2026-09-21:** Let the controls, hierarchy, and outcome
labels carry the experience. Remove captions that explain an obvious action,
repeat a result, or describe where content will appear. Ordinary review, approval,
refusal, and return screens do not need explanatory paragraphs. Keep short text
only when it changes a decision or enables recovery, such as an uncertain payment
outcome, an operation that continues after closing, required consent, or an
unconfirmed fee. Show one outcome label per payment result. Localize every status
shown to the user, including order filters and details; never display backend
enum values or use them as a fallback. Unknown states must say that confirmation
is needed. An approved request must remain distinct from a completed payment.

| Avoid | Prefer, when factually correct |
| --- | --- |
| “Click here to initiate a new transfer” | `Send` plus a recognizable icon |
| “Your transaction has been successfully completed” | `Sent` plus recipient and amount |
| “An unexpected error has occurred” | A specific reason and `Try again` or `Check status` |
| “No transactions are currently available” | `No payments yet` and the appropriate first action |
| Icon-only primary navigation | Icon plus a short destination label |
| Tooltip as the only explanation | Visible context where the decision is made |

Do not use emoji as the product icon system. Do not use flags as the only currency identifier. Do not rely on animation, color, or a symbol alone to communicate a consequential state.

---

## 8. Motion system: authored, coherent, interruptible

**User-directed reference update, 2026-09-21:** Apple Inc.’s animation and motion are the motion reference for this app. Apply Apple’s published principles of purposeful, brief feedback; continuous object identity; interruptible, redirectable motion; and natural spring settling. The visual hierarchy and product identity in this document remain in force, and the implementation retains Android conventions and the existing runtime.

Use critically damped or comparably restrained native springs for physical surface/control movement, preserving velocity when interactions redirect an animation. Keep exact money values, authentication, outcome truth, reduced motion, and immediate access to actions independent of those springs. Parameters remain original project tuning, not claimed Apple internals. References: [Apple HIG: Motion](https://developer.apple.com/design/human-interface-guidelines/motion), [WWDC23: Animate with springs](https://developer.apple.com/videos/play/wwdc2023/10158/), [WWDC18: Designing Fluid Interfaces](https://developer.apple.com/videos/play/wwdc2018/803/).

### 8.1 Motion grammar

Every interactive component must define its applicable rest, press, focus, selection, disabled, busy, success, and error treatments. Every meaningful addition, removal, value change, and route transition must define continuity.

Elements may **inherit their container's motion**. A paragraph does not need independent animation for each word. An entire transaction row can move as one object. Coverage matters; independent animation count does not.

Use motion to answer: **Where did this come from? What did my action affect? What changed? What can I do now?**

Keep one dominant motion at a time. Secondary movement should support it. Surfaces have mass; controls feel responsive; numbers remain stable. Avoid rubbery overshoot, elastic money values, perpetual levitation, and competing loops.

A collection of generic fade-ins and 0.98 button scales is not enough. The core flow must contain distinctive object continuity, controlled surface transformations, and event-specific feedback that remain compelling when watched as a complete interaction.

All motion must be written for this project in code: transitions, keyframes, springs, SVG paths/masks, gesture responses, and orchestration. Existing animation engines may execute this code. Imported choreography, prebuilt effect presets, downloaded Lottie/Rive scenes, stock animated illustrations, GIFs, and video loops are not the motion system.

### 8.2 Timing tokens

Starting targets, not rigid timers. Use logical units for motion distance.

| Token | Target | Use |
| --- | --- | --- |
| `feedback-in` | 70–100 ms | Press compression or tint response |
| `feedback-out` | 140–200 ms | Release and settle |
| `micro` | 140–200 ms | Icon, small state, copy-feedback change |
| `content` | 180–260 ms | Local reveal, error insertion, value replacement |
| `navigation` | 260–340 ms | Push, return, peer context change |
| `surface` | 320–420 ms | Sheet or shared-surface transformation |
| `signature` | 480–700 ms total | Complete important choreography; trailing decoration is nonblocking |
| `stagger` | 24–36 ms | Up to four related groups, not every row or word |
| `reduced` | 0–100 ms | Direct state change or restrained opacity transition |

Use `cubic-bezier(0.22, 1, 0.36, 1)` as a starting reveal curve, `cubic-bezier(0.4, 0, 1, 1)` for shorter exits, and `cubic-bezier(0.2, 0.8, 0.2, 1)` for controlled morphs.

For engines using stiffness/damping/mass, start around `500 / 44 / 1` for firm controls and `280 / 30 / 1` for larger surfaces. These parameters are engine-dependent: tune by observed settling, distance, velocity, and device performance. Do not assume cross-platform numerical equivalence.

Ordinary content displacement should usually be 4–16 units. Sheets may travel from their actual edge. Follow platform direction conventions and mirrored layouts where appropriate.

### 8.3 Motion coverage matrix

| Element or event | Required treatment | Important constraint |
| --- | --- | --- |
| Button press | Small visual compression, typically 0.98, plus tonal response; release settles. | Stable hit area; activate by normal input semantics, not merely pointer-down. |
| Icon action | Container responds; icon may make one restrained directional movement. | Label/accessibility name remains unchanged unless the action state changes. |
| Focus | Clear ring appears immediately; a subtle opacity finish is optional. | Never delay focus visibility for animation. |
| Selection/chip | Selection background moves or resolves into the target. | Selected semantics update immediately. |
| Bottom navigation | Active indicator glides; content changes with a restrained context transition. | No full home entrance replay; preserve tab state. |
| Push/back | Related forward and reverse spatial transitions. | Back remains interruptible; restore position and focus. |
| Sheet/dialog | Surface rises from its origin or edge; backdrop resolves behind it. | Proper focus handling; no invisible click-blocking layer after exit. |
| Drag dismissal | Surface follows the gesture and settles or returns. | Visible close alternative; do not hijack scrolling. |
| Amount change | Changed glyph crossfade or short local movement. | Never tween through fictional amounts or delay editing. |
| List insertion/removal | Reserved or smoothly reconciled space with a short reveal. | Do not move the active touch target or reset scroll. |
| Toggle | Thumb and track respond coherently. | Pending remote state is not represented as confirmed. |
| Error | Local context stays; error appears with a restrained reveal. | No shaking the entire screen; recovery is immediately usable. |
| Banner/toast | Short edge entrance and quiet exit. | Consequential information also persists in the relevant screen. |
| Placeholder to data | Equal-footprint replacement and brief crossfade. | No geometry jump or fake amount. |
| Theme change | Short surface/color transition, when supported. | No expensive full-screen blur; preserve contrast. |
| QR acquisition | Authored reticle resolves around the acquired region once. | Recognition does not commit a transaction. |
| Confirmed success | Original SVG status mark resolves as the receipt forms. | Only after authoritative confirmation. |

Hover may add a subtle surface response on pointer devices. It must not be necessary for discovery. Pause nonessential motion when the screen is hidden or the element is offscreen.

---

## 9. Signature choreographies

Implement these as connected sequences, not an assortment of unrelated effects. Adapt unsupported examples to the app's actual core task.

### 9.1 Home settles into your money

At entry, establish the usable application shell immediately. Across roughly 0–320 ms, reveal the account context, balance, action group, and first activity group with shared timing and small displacement. A material highlight may finish settling by about 480 ms, without affecting controls.

Keep background identity consistent with the preceding app-owned surface where appropriate. Do not add a custom splash sequence. Do not replay this entrance when returning to home, resuming a tab, or receiving a background data update.

**UX purpose:** orient attention to money and available actions without making the user read an introduction.

### 9.2 A selected object keeps its identity

A recipient avatar, account tile, or card becomes the anchor of the next surface. Its position and scale interpolate continuously while secondary content resolves around it. Reverse the relationship on back navigation.

Prefer movement of containers and clipped surfaces over scaling live text. Keep one accessible semantic instance; presentation clones, when unavoidable, must not become duplicate focus or announcement targets.

**UX purpose:** confirm the selected object and remove the feeling of jumping between unrelated pages.

### 9.3 Commitment becomes a receipt

At valid, explicitly authorized submission:

- **0–100 ms:** acknowledge the press; start the genuine request immediately when authorization permits.
- **80–280 ms:** transform the action region into a task-specific progress surface, keeping recipient and amount stable.
- **At the real outcome:** join directly into the appropriate result. A confirmed success can draw an original SVG check over roughly 160–220 ms while the same surface becomes the receipt.
- **By roughly 480–700 ms in a fast-success case:** the remaining decorative motion settles. Result content and the next action must already be usable.

For a near-instant response, retain a smooth surface transformation rather than flashing a busy state for one frame. The transition is the visual bridge; do not create a fake delay before dispatch or a fake “processing” story after confirmation.

For a slower response, hold a composed, truthful in-progress state. A small authored path or tone motion may indicate activity, but must not pretend to show measured completion. Do not repeatedly restart the commitment sequence.

**UX purpose:** make confirmation feel like the direct, tangible consequence of the user's action.

### 9.4 The receipt belongs in history

When the user leaves a completed receipt for activity, relate its amount/identity to the matching activity item through a short shared-element or coordinated transition. Use the actual transaction identifier. If the row is offscreen, use a restrained list-level transition instead of scrolling the user unexpectedly.

**UX purpose:** explain where the record can be found later.

### 9.5 Optional card or scanner detail

For supported cards, a selected card may lift from its stack with a single authored highlight sweep. Keep card numbers and control labels undistorted. For scanners, a reticle may contract once on recognition before presenting the merchant review surface.

**UX purpose:** acknowledge selection or recognition. Neither effect is a substitute for actual card state or a valid QR payload.

---

## 10. Perceived latency without false state

### 10.1 Two separate clocks

Maintain two independent concerns:

- **Business state:** actual request, authorization, transaction, and reconciliation state.
- **Presentation state:** current surface, transition progress, and visual joining point.

Start work and presentation in parallel whenever valid. Never place business dispatch behind a cosmetic timeout. Never derive success from elapsed animation time. An animation-end callback may finalize presentation cleanup; it must not authorize, submit, or declare a payment complete.

A fast response does not require an abrupt visual cut. Apply the real result immediately to the state model, then continue into a coherent result presentation. Normal result joining should occur within about 120 ms; extend the **nonblocking** visual settle rather than hiding a known outcome behind another loop. Errors and user cancellation should interrupt decoration immediately.

Navigation and data reveals can retain their designed 260–420 ms motion even when content is already ready. Their duration must not become an additional wait after the transition itself. Reduced-motion mode has no cosmetic minimum duration.

### 10.2 Latency ladder

All thresholds below are initial presentation policies to tune against measured application behavior, not network guarantees.

| Elapsed unresolved work | Presentation |
| --- | --- |
| 0–180 ms | Show immediate feedback and begin the natural transition. Do not flash a loader. |
| 180–800 ms | Use the existing surface transformation as the bridge. Keep task identity visible. |
| 800 ms–3 s | Settle into compact, honest activity within the same surface: for example, `Sending`. No fake percentage. |
| Beyond about 3 s | Add concise delayed-state context and available navigation. Do not indefinitely repeat a triumphant anticipation animation. |
| Beyond about 8–10 s | Use a durable pending/check-status presentation when appropriate; preserve the transaction reference and supported recovery. Stop attention-seeking loops. |
| Offline or ambiguous outcome | Explain the actual uncertainty; distinguish “not submitted” from “submitted, result unknown.” |

Do not label internal stages such as `Verified`, `Approved`, or `Sent` without evidence. A path animation is activity feedback, not fabricated progress measurement.

For read-only data, retain usable existing content during revalidation. On cold entry, render a stable shell and local interaction affordances as early as possible. Use geometry-matched placeholders only for genuinely missing content, preferably after a short delay to avoid flashes. Never substitute zero for an unknown balance. Reveal independent regions as they become ready; do not block the whole screen behind the slowest request.

Prefetch likely **read** routes and safe assets using the app's existing architecture. Do not prefetch mutations, access private content before authorization, or store financial data insecurely merely to improve perceived speed.

### 10.3 Transaction state model

Map these conceptual states to the existing backend's real semantics:

```text
editing -> reviewing -> authenticating (when required) -> submitting
submitting -> succeeded | definitively_failed | pending | outcome_unknown
pending / outcome_unknown -> succeeded | definitively_failed
                            through authoritative reconciliation
```

A scheduled or merely authorized transaction needs its own accurate product state; do not force it into `succeeded`. Later reversal or refund events are subsequent lifecycle events, not an animation rollback.

After a timeout or disconnected response, preserve the operation identifier and reconcile before offering another potentially duplicate payment. Frontend button locking is not a substitute for server/provider idempotency. Reuse the existing backend's supported idempotency contract; provider rules differ. Stripe's documentation is an example of request-level idempotency, not a requirement to adopt Stripe. [R7]

An unresolved operation must survive screen changes and, where the architecture permits, safe app resumption. Dismissing its presentation does not cancel it. Offer cancellation only when the actual operation supports it and the cancellation result is known.

Version or otherwise reconcile conflicting responses so that an older result cannot overwrite a newer authoritative state. Do not launch a new money mutation automatically on reconnect or UI remount.

### 10.4 Optimism boundaries

Immediate local updates are appropriate for input, selections, and reversible local preferences. Remote preferences may need rollback.

Money movement, available funds, card security changes, and transaction completion must stay tied to the real contract. A pending activity entry is allowed when accurately labeled; it is not a confirmed receipt. Do not silently deduct or add spendable funds as though an uncertain payment settled.

---

## 11. Asset policy: SVG first, generation only when essential

### 11.1 Default order

1. Use layout, CSS/native drawing, gradients, and simple geometry when sufficient.
2. **Hand-author original SVG** for icons, small illustrations, empty states, card materials, masks, and motion paths.
3. Reuse suitable existing project-owned assets and legitimate user-provided content.
4. Use an available image-generation tool or skill only for an indispensable visual that cannot reasonably be achieved with the above, such as a genuinely necessary photoreal material.

A decorative gradient, abstract blob, ordinary payment card, checkmark, empty wallet illustration, avatar fallback, or loading treatment does **not** justify image generation. Most implementations of this design should require no generated raster assets at all.

Do not default to stock imagery, screenshot backgrounds, raster icons, remote hotlinks, or downloaded illustration packs. Do not fabricate recipient photos or merchant identities. Existing authentic identity imagery can remain where authorized.

### 11.2 SVG construction rules

Use a consistent 24 × 24 icon grid; typically 1.75–2 unit strokes, matching cap/join behavior, and deliberate optical alignment. Active variants can change fill or emphasis without changing identity.

Use `viewBox`, themeable colors, reusable shapes, and unique IDs for masks/gradients. Avoid embedded raster images, scripts, external resource dependencies, excessive path points, and expensive animated filter chains. Keep meaningful dynamic text as native text or HTML rather than drawing it into paths.

Decorative SVGs must be excluded from the accessibility tree. Meaningful standalone SVGs need an appropriate accessible name; controls should own their accessible label. Sanitize any externally supplied SVG rather than treating it as trusted code.

**Algorithmic exception:** functional QR/barcode data must be generated by a reliable encoder and may be rendered as SVG. Do not hand-sketch machine-readable modules. This is not an exception for ordinary UI icons.

### 11.3 Original motion assets

Author status paths, check drawing, reticle resolution, material sweeps, and object transformations directly in code. Reuse motion primitives across screens without reusing one generic animation for every meaning.

An engine or renderer is allowed; canned animation content is not. Do not write a custom rendering engine merely to satisfy “hand-authored.” Own the design and choreography, not every low-level runtime.

### 11.4 Exceptional image generation

Before invoking generation, record:

- The indispensable UX purpose and why coded geometry/SVG is insufficient.
- Required dimensions, crop, theme treatment, and performance budget.
- A prompt without user financial data, credentials, or sensitive identity information.
- The output path, a coded fallback, and how the result will be used.

Use the actual available image-generation capability; do not merely leave a prompt or claim an asset was generated. Review the output, optimize it, and keep interface text out of the image. Animate the resulting layer with original code rather than generating a video to imitate UI motion.

If no generation tool is available, use the coded fallback and document the tradeoff. Do not invent a tool result or block the core implementation on decorative imagery.

Maintain an asset manifest with `name, path, purpose, authoring method, size, accessibility treatment, motion behavior`, plus the rationale and generation prompt for any exceptional generated asset.

---

## 12. Accessibility, resilience, and localization

Target WCAG 2.2 AA for web surfaces, and equivalent platform accessibility practices for native surfaces. Also honor reduced-motion preferences as an explicit product requirement; interaction-triggered motion control is addressed by WCAG 2.3.3 at Level AAA, not AA. [R3, R4]

Use normal-text contrast of at least 4.5:1, qualifying large-text contrast of at least 3:1, and at least 3:1 for necessary control/state graphics. Support text enlargement, reflow, keyboard navigation, visible focus, programmatic names/roles/values, and nonvisual status communication. These requirements derive from WCAG; the project's palette still needs testing. [R3]

Reduced-motion mode must remove spatial travel, scale effects, parallax, animated material sweeps, and looping activity decoration. Use immediate states or brief opacity transitions. Never gate functionality on an animation event that may not occur. On the web, respond to `prefers-reduced-motion`; use corresponding native preferences on native platforms. [R4, R8]

Additional project requirements:

- Preserve tap alternatives for drag or swipe interactions. Respect system back and familiar dismissal behavior.
- Manage overlay focus, Escape/back handling, and focus restoration. Keep focused content visible above keyboards and fixed controls.
- Announce meaningful status changes once; do not read every animated glyph, cloned element, or progress frame.
- Haptics, when supported and permitted, may confirm selection or outcome. They are optional reinforcement, never the only feedback. Do not fake biometric or provider confirmation haptics.
- Keep the experience complete without sound. Do not add autoplay audio.
- Preserve locale, right-to-left layout where applicable, currency rules, long names, and translated strings. Do not force the interface into English because this document is English.
- Do not expose sensitive balances before authentication or retain unmasked financial data in unsafe caches. Use existing privacy and app-background protections.

Never claim that a design is accessible solely because a checker passed, or universally understandable solely because the designer finds it obvious.

---

## 13. Implementation architecture and performance

### 13.1 Keep responsibilities separate

Adapt these responsibilities to the existing stack and directories:

```text
Design tokens           colors, type, space, geometry, elevation
Motion tokens           timing, curves, springs, reduced-motion policy
Interaction primitives  press, selection, focus, surface transitions
Domain state            authoritative money and transaction semantics
Presentation coordinator state-to-motion mapping and cancellation
SVG assets              original, themeable vector components
Screen compositions     actual journeys built from shared primitives
Verification            component, journey, visual, state, and performance tests
```

Prefer the existing animation engine and native platform transitions. For a web stack, hand-authored CSS/Web Animations or an already installed motion runtime are appropriate. For native stacks, use their existing supported animation/gesture system. Consult documentation matching installed versions before introducing APIs. Do not mix several competing runtimes or add a heavy dependency for a simple effect.

Use stable identifiers for shared objects and transaction records. Keep visual state declarative and data-driven. Handle interruption, cancellation, route changes, stale responses, and unmount cleanup deliberately. An interrupted transition must not leave ghost layers, locked scrolling, lost focus, or disabled controls.

Never animate `all` properties globally. Never chain arbitrary sleeps to manufacture a smooth demo. Never rewrite transaction behavior inside an animation component.

### 13.2 Performance targets

These are project targets to measure, not asserted results:

| Area | Target |
| --- | --- |
| First visible response to input | Within 100 ms at the 95th percentile on the named test device |
| Main interaction smoothness | Aim for at least 95% of animation frames within the device's refresh budget in representative recordings |
| Refresh budget | Approximately 16.7 ms at 60 Hz; 8.3 ms at 120 Hz |
| Input continuity | No dropped digits, queued taps, or animation-induced editing delay |
| Layout stability | No unexpected amount, button, or focused-element jumps as data/assets arrive |
| Ordinary icon weight | Target under 3 KB per optimized SVG; justify larger exceptions |
| Decorative vector weight | Target under 30 KB per illustration; profile complex paths/filters |
| Exceptional raster | Target under 200 KB at intended display size; load outside the critical interaction path |
| Idle behavior | No continuous nonessential animation or unnecessary background rendering |

For web animations, favor transform and opacity when appropriate; properties triggering layout or paint require profiling. That is a performance technique, not a guarantee that every transformed layer is cheap. [R6]

Do not animate giant blurs, full-screen filter stacks, or complex SVG filters every frame. Precompute suitable paths and static materials. Release unnecessary compositing hints after use. Keep state changes local; do not rerender the entire app for each animation frame.

On constrained devices, first remove costly decoration, then simplify motion distance and layering. Preserve immediate feedback, semantic state, and spatial understanding. A simpler smooth transition is better than a visually elaborate dropped-frame sequence.

---

## 14. Verification and completion criteria

### 14.1 Build one complete vertical slice, then propagate

First finish the actual primary journey end to end: home entry → destination → amount/review → real or explicitly isolated fixture submission → confirmed, pending, and failure outcomes → activity/receipt.

Use this slice to validate type, surfaces, original SVGs, motion, network behavior, and accessibility together. Then apply the system to every audited in-scope route. Do not polish a static home indefinitely while leaving the core payment experience unchanged.

### 14.2 Required stress scenarios

| Scenario | What must remain true |
| --- | --- |
| Responses at 0, 80, 250, and 800 ms | Smooth continuity; no loader flash or artificial pre-dispatch wait. |
| Responses at 2, 8, and 30 seconds | Honest escalation; preserved context; no endless staged completion animation. |
| Offline before submission | No misleading success; draft and recovery remain usable. |
| Connection lost after possible acceptance | Unknown/pending state reconciles without an automatic duplicate payment. |
| Repeated rapid confirmation taps | One logical payment operation; backend-supported deduplication respected. |
| Old response arrives late | It cannot overwrite a newer authoritative state. |
| Navigate away, return, or resume | Operation identity survives; no unintended resubmission or repeated celebration. |
| Interrupt a sheet/route transition | No stuck overlay, lost input, duplicate focus target, or broken back behavior. |
| Fee, balance, or quote changes before commitment | Revalidate and require an appropriate updated review; never silently change the user's agreed transaction. |
| Card security mutation fails | Show the real resulting state, not an optimistic false security claim. |
| Permission denied or authentication canceled | Clear recovery without discarded safe input or an unsolicited retry. |
| Empty data, long names, large amounts | No fake data, clipping, unusable buttons, or broken hierarchy. |
| Reduced motion, keyboard, screen reader | Same task completion and truthful status without relying on visual motion. |

Test mobile layouts around 320, 360, 390, and 430 logical pixels wide, plus supported tablet/desktop layouts. Include short viewports, keyboard-open states, text at 200%, and dark mode when in scope. Use the actual supported browser/device matrix, not only a desktop-sized preview.

### 14.3 Human comprehension check

When participants are available, ask five people unfamiliar with the product to find the primary payment action, select the intended recipient, identify the exact debit before confirming, and locate the resulting receipt. Give no feature tour. Include varied digital confidence and relevant accessibility needs where feasible.

An initial target is at least four of five finding the primary action within five seconds without coaching, with every participant correctly identifying recipient and debit before commitment. Record actual observations, hesitation, and mistakes; this small check is not proof of universal usability.

Until such testing occurs, label this criterion **not tested**. Never manufacture participants, quotes, success rates, or accessibility findings.

### 14.4 Evidence and iteration

Run the existing build, lint/type checks, and relevant tests. Inspect rendered screens. Capture the main journey in motion when tooling allows; a screenshot alone cannot verify interruption behavior or choreography.

Perform at least two focused review-and-fix passes. Review hierarchy, visual refinement, friction, motion continuity, truthfulness under latency, accessibility, and constrained-device behavior. Fix concrete observed problems rather than merely adding more effects.

Keep a compact verification record with `scenario, environment, expected result, observed result, evidence, pass/fail/not tested`. Record measured performance with the device and method. Do not claim a live provider path was tested using only fixtures.

### 14.5 Definition of done

- [ ] Existing supported functionality, authentication, data contracts, and locale are preserved.
- [ ] The primary task is obvious, reachable, and complete end to end.
- [ ] All audited in-scope routes and consequential states use the new system.
- [ ] The interface is recognizable through hierarchy and objects, not explanatory paragraphs.
- [ ] Necessary labels, fees, recipient identity, currencies, and confirmation remain clear.
- [ ] Tokens and reusable components produce consistent surfaces, spacing, type, and controls.
- [ ] Every applicable interaction and state change has defined motion or an intentional reduced-motion equivalent.
- [ ] At least three appropriate signature relationships are implemented in the actual core flow, not a separate showcase.
- [ ] Default visual assets are hand-authored SVG/code; any generated-image exception is justified and documented.
- [ ] Motion is original, interruptible, and independent of financial truth.
- [ ] Fast responses feel fluid; slow and ambiguous responses remain honest and recoverable.
- [ ] No fake success, decorative QR, fabricated balance, duplicate submission path, or endless loading theater remains.
- [ ] Reduced motion, keyboard behavior, focus, contrast, and text scaling are checked with the available tools.
- [ ] Performance is measured where possible, with limitations stated rather than invented.
- [ ] Build/test results, visual evidence, outstanding limitations, and modified areas are documented.

A visually impressive home screen with a broken payment path is not done. A technically correct app with generic, disconnected motion is not done. The standard is **beauty, comprehension, continuity, and trust working together**.

---

## 15. References and evidence boundaries

Public material reviewed on 2026-09-21. The references support benchmark observations or implementation principles; they do not supply this project's exact tokens, timing system, or promised results.

**[R1] Revolut — “The era of ease has arrived with Revolut 10,” 10 October 2023.** Official description and published imagery of simplified home, account switching, and personalization. Historical product reference, not a claim about the exact current release.\
`https://www.revolut.com/blog/post/revolut-10/`

**[R2] Denis Kovalev for Revolut — “Revolut Home Appearance.”** Public designer showcase describing the authentication-background and smooth home-appearance treatment. Does not disclose internal motion constants.\
`https://dribbble.com/shots/24786228-Revolut-Home-Appearance`

**[R3] W3C — Web Content Accessibility Guidelines (WCAG) 2.2.** Normative accessibility reference for contrast, interaction, semantics, and related web requirements.\
`https://www.w3.org/TR/WCAG22/`

**[R4] W3C — Understanding SC 2.3.3, Animation from Interactions.** Explains the Level AAA criterion concerning the ability to disable nonessential interaction-triggered motion.\
`https://www.w3.org/WAI/WCAG22/Understanding/animation-from-interactions.html`

**[R5] W3C — Understanding SC 3.3.4, Error Prevention (Legal, Financial, Data).** Explains confirmation, correction, or reversibility for consequential submissions.\
`https://www.w3.org/WAI/WCAG22/Understanding/error-prevention-legal-financial-data.html`

**[R6] web.dev — How to create high-performance CSS animations.** Primary web-platform guidance on choosing and profiling animated properties.\
`https://web.dev/articles/animations-guide`

**[R7] Stripe — Idempotent requests.** One provider's documented idempotency behavior; consult the actual project's provider/backend contract instead of assuming identical semantics.\
`https://docs.stripe.com/api/idempotent_requests`

**[R8] MDN — prefers-reduced-motion.** Web implementation reference for responding to a user's motion preference.\
`https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@media/prefers-reduced-motion`

## 16. Notification approval and return contract

A payment request commonly arrives while the user is in another app or at Home.
Tapping its notification opens a focused approval task with the existing server
review and authentication journey. The merchant and exact amount remain anchored
through review, authentication, processing, and the actual server result.

Approval or refusal does **not** automatically dismiss the result. Keep the
confirmed result visible and present a clear **돌아가기** button. Only the user's
explicit return action closes the notification task, revealing the previous app
or Home through Android's task history. Never force-open the wallet or relaunch a
remembered external package. The ordinary in-app receipt/history journey remains
available when the user starts in the wallet.

Errors and unknown outcomes remain truthful and recoverable. Closing presentation
does not cancel an already-dispatched operation. Repeated notifications must not
replace current consent, clear an entered PIN, or create another payment owner.
Normal device unlocking still applies to notification entry from the lock screen.
