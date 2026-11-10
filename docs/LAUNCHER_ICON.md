# Launcher icon proposal: 106 + AI

Owner selection pending. This PR must stay unmerged until the owner picks a concept.

Option 1 is prepared: a hand-drawn white 106, gold AI and a small four-point spark on the existing navy background. No font, municipal logo, coat of arms or external asset is used. Geometry was drawn for this project and is covered by this repository's license. This is not an official municipal app or an official municipal mark.

- Navy: #2C537E. Gold: #D8A038. White: #FFFFFF.
- Adaptive icon: foreground and solid navy background in a 108dp viewport.
- Geometry remains in the central safe region; checked with circle and rounded-square masks, including 48px and 32px previews.
- Android 13 themed icon: one-color version through the monochrome layer. Launcher chooses its tint.
- minSdk is 26, so the app supports adaptive icons on every supported device. No raster fallback is required.
- Application icon and roundIcon are wired in the manifest. The existing Quick Settings tile icon is unchanged.

The repository checker verifies both manifest references and all adaptive layers. The screenshot preview was rendered from the same path geometry. Android build and launcher inspection are still CI/device checks, not covered by the static checker.
