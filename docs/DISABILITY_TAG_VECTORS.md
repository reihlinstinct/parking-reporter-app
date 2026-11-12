# Disability-tag vector assets

Two original geometric redraws, supplied as SVG and Android VectorDrawable XML. They are app symbols, not official government artwork or reproductions of a permit/card. No photograph, government seal, card text, plate or personal data is included. Use under this repository's MIT licence; no third-party vector was copied. The source photograph supplied by the user is a visual reference only, not distributed.

Official context checked November12,2026:
- https://me.health.gov.il/older-adult/services-rights/benefits-and-eligibilities/disabled-parking-placard/ lists regular and wheelchair tag types, including Ministry of Defence variants.
- https://www.btl.gov.il/benefits/Disability/otherbenefits/Pages/%D7%AA%D7%92%20%D7%97%D7%A0%D7%99%D7%94%20%D7%9C%D7%A8%D7%9B%D7%91%20%D7%A0%D7%9B%D7%94.aspx describes wheelchair-tag eligibility.

These pages do not establish a reusable image licence or the registry's numeric type-to-icon mapping. No claim of public-domain government art is made. The owner explicitly confirmed type1=triangle and type2=wheelchair. `DisabilityTagType.fromRegistry` centralizes that mapping; all other/missing values return UNKNOWN. UI integration remains separate. A tag icon is not a determination that parking is lawful.

## Files and use

`docs/assets/disability_tag_wheelchair.svg` and `docs/assets/disability_tag_triangle.svg` have matching path geometry in `app/src/main/res/drawable/ic_disability_tag_wheelchair.xml` and `ic_disability_tag_triangle.xml`. Viewport64, default32dp, fixed color, no RTL auto-mirroring. Do not apply a monochrome tint to these multicolor assets. Use `Color.Unspecified` in Compose. At24dp they remain distinct;32dp is preferred for the registry result. Give a clickable row at least48dp if interaction is added.

Content descriptions are supplied in the five-language app catalog under `registry.tag.wheelchair_icon` and `registry.tag.triangle_icon`. When adjacent text already announces the same tag type, set the icon contentDescription to null to avoid duplicate speech. Otherwise use the localized description. Do not expose an unlabeled color-only status. Unknown/unavailable tag type must use explicit text, not either of these type-specific icons.

Contrast calculated from sRGB relative luminance: white wheelchair on blue #12449A is9.05:1, white against green #087F5B is5.00:1, and dark #17231F dots against white are16.20:1. The green triangle has a dark outline on light surfaces; its white center preserves the dot symbol on dark surfaces. These are original high-contrast app colors, not a claim of exact official ink matching. SVG source and XML paths are checked for parity; actual Android rendering and TalkBack integration remain part of #333 implementation, not claimed by this asset-only PR.

## Live public-registry check

On November12,2026 the public resource `c8b9f9c8-4612-4068-934f-d4acd2e3c06e` exposed exactly two distinct numeric `SUG TAV` values. Filtered counts:1=604611,2=34033,3=0,4=0. Only the two top symbols requested by the user are therefore supplied, with no IDF/Ministry of Defence variants inferred. This is the shape of the public dataset, not a claim that only two physical permit designs exist.

Official source URLs:
- https://data.gov.il/api/3/action/datastore_search?resource_id=c8b9f9c8-4612-4068-934f-d4acd2e3c06e&fields=SUG+TAV&distinct=true&limit=100
- https://data.gov.il/api/3/action/resource_show?id=c8b9f9c8-4612-4068-934f-d4acd2e3c06e
- https://data.gov.il/api/3/action/package_show?id=11369651-1c70-4d8f-8090-ee49354a7c52

The registry schema lists only vehicle number, tag-issue date and numeric type. Neither resource nor package metadata defines which numeric type maps to which symbol. The owner supplied the final mapping: type1=triangle, type2=wheelchair. This mapping is owner-confirmed, not attributed to an official dictionary; it is centralized in one small map and unknown values stay neutral. No UI/network integration is added here. No plate-level data was needed for the type check. Dataset metadata says CC BY; that data licence does not prove the photographs/symbol artwork is reusable. These assets are independent simple geometric redraws under the repository MIT licence.

## Light and dark boundaries (#347)

Each vector has an untinted white exterior silhouette around an inset (84%) colored symbol. On a light surface the dark colored/outlined inner contour remains visible; on a dark surface the white exterior defines the boundary. Internal white/blue contrast alone is not a theme-accessibility claim. Android instrumentation renders both drawables at24/32dp on white/black, checks real raster contour pixels and captures a Material3 light/dark grid. SVG/Vector group transforms are covered by parity tests. Text remains required in the consuming UI; unknown types do not use these symbols.
