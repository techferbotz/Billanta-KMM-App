# App icon

`app-icon.svg` is the source of truth; `app-icon-1024.png` is its 1024×1024 raster.

Where each form ends up, and why:

| target | file | form |
| --- | --- | --- |
| Android 26+ launcher | `androidApp/.../drawable-v24/ic_launcher_foreground.xml` + `drawable/ic_launcher_background.xml` | **vector** — hand-converted from the SVG, paths and gradients unchanged |
| Android 24–25 launcher | `androidApp/.../mipmap-*/ic_launcher{,_round}.png` | raster, 48–192 px |
| iOS | `iosApp/.../AppIcon.appiconset/app-icon-1024.png` | raster, 1024 px, **no alpha channel** |

SVG is not a shippable asset on either platform: Android needs a `VectorDrawable` (which is what the
adaptive foreground is — vector, resolution-independent, just a different file format), and an iOS
app icon must be PNG. So the vector form is used everywhere a vector is accepted.

The iOS PNG is re-encoded as RGB with no alpha channel — App Store Connect rejects app icons that
carry one, even when every pixel is opaque, as it is here.

To regenerate the rasters after changing the SVG: export a 1024×1024 PNG over the brand purple
(`#5B4FE0`), then resize with `sips -z N N` and strip/mask alpha as above.
