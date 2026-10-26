# Copenhagen brand artwork

- `copenhagen-logo-original.png` is the user's approved 1254 × 1254 source, copied without alteration on 2026-09-22. Keep this as the source of truth for in-app branding.
- `copenhagen-launcher-padded.png` is a built-in ImageGen edit of that source. It extends the blue background and gives the white symbol enough space for Android's adaptive icon masks. It is used for the launcher and system app identity.
- `../../tools/export-brand-assets.sh` exports the committed, lossless WebP resources with ImageMagick. No network or generative tool is used during exports or builds. The originals are outside `app/src`, so they are not packaged into the APK.

The in-app logo is 384 × 384 pixels (up to 64 dp in the interface). The launcher artwork is 432 × 432 pixels, equivalent to a 108 dp adaptive layer at xxxhdpi. Both use `drawable-nodpi`; Compose supplies explicit bounds and the launcher's bitmap drawable fills its bounds. No duplicate density copies are needed for this minSdk 26 app.

The harbor launch animation uses a separate 960 × 960 lossless WebP export of the unchanged original. Native Canvas clips that bitmap into the dock and boat paths in `res/values/brand_paths.xml`, preserving the original white ceramic material while moving the boat. It dissolves back to the complete original bitmap before showing the app. No new raster artwork was generated for the animation. The process-scoped replay rule and exact timing are documented in [the harbor splash implementation record](../../docs/REIMAGINED.md).

The launcher foreground deliberately retains the full blue backdrop and baked-in 3D shading. A separate blue fallback layer fills its bounds. Android owns the external mask; no round corners are baked into either source. The two attempted transparent cutouts had visible artifacts and are not used or included here.

On 2026-09-23, the launcher drawable gained a 16.6667% inset on each side. Android's adaptive icon expands child layers to 150% of the visible icon; the inset cancels that enlargement so the padded artwork's margins remain visible on the home screen. The themed silhouette uses a corresponding 30.6667% inset (the earlier 21% padding, scaled by two thirds). The source image and in-app brand placements retain their existing artwork.

The native vector in `app/src/main/res/drawable/ic_notification.xml` follows the source silhouette, with open circular and rectangular clasp cutouts. It is white on transparent for Android's small notification icon. `ic_launcher_monochrome.xml` reuses it with proportional padding for Android 13+ themed icons. Functional shield, payment, profile and navigation glyphs retain their meaning.

Brand placements: main-tab wordmark, welcome and password login, settings app/version footer, launcher/app switcher, startup and notification small icon. `BrandLogo` uses the original artwork with a rounded display clip and no tint; adjacent app names avoid duplicate accessibility labels.

Adaptive layout reference: [Android adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive). Startup reference: [Android splash screens](https://developer.android.com/develop/ui/views/launch/splash-screen).

## Final ImageGen prompt (built-in tool)

> Use case: precise-object-edit. Asset type: Android adaptive launcher artwork with safe padding. Edit target: the supplied approved logo. Preserve the entire original artwork, including its vivid cobalt blue background, white ceramic three-dimensional wallet C symbol and separate pointed clasp, highlights and cast shadows. Create a fully OPAQUE square PNG by uniformly reducing the ORIGINAL image to 73 percent of the final canvas width and height, centered, and seamlessly extending ONLY the surrounding blue gradient to all four outer edges. The white C symbol must occupy approximately 46 percent of final canvas width and height, centered, spanning x=27%..73%, y=27%..73%; all white artwork must fit within a circle of diameter 61% of the canvas. This is solely canvas expansion / icon safe-margin preparation. Keep the geometry, proportions, colors and details of the original exactly intact. Smooth continuous blue background with no visible inset rectangle, no seam, no border, no transparency, no rounding of the square outer canvas, no text, no additional elements.
