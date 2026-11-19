"""Offline source/asset contract checks; Android build and pixels remain separate proof."""
from pathlib import Path
import hashlib
import gzip
import json
import unittest
ROOT=Path(__file__).resolve().parents[1]

class OnlineAddressContractTest(unittest.TestCase):
    def test_all_published_polygons_preserve_pinned_cbs_source(self):
        raw = gzip.decompress((ROOT / "tools/address-lookup/sources/cbs-jerusalem-2022.geojson.gz").read_bytes())
        self.assertEqual("ad780e9e8a977ad7f55618a4a032af35053b8f3e013de9be7e7b9c7332e02310", hashlib.sha256(raw).hexdigest())
        features = json.loads(raw)["features"]
        self.assertEqual(243, len(features))
        for feature in features:
            rid = "cbs-" + str(feature["properties"]["YISHUV_STAT_2022"])
            published = json.loads((ROOT / "tools/address-lookup/data/v1" / (rid + ".json")).read_bytes())
            self.assertEqual(feature["geometry"]["coordinates"], published["polygons"])

    def test_submission_does_not_publish_regional_directory_as_global(self):
        shell = (ROOT / "app/src/main/java/io/github/reihlinstinct/reporter/ui/AppShell.kt").read_text()
        self.assertNotIn(".also { streetsDirectory = it }", shell)
        self.assertIn("val directory = streetDirectoryLoader?.invoke()", shell)

    def test_index_pin_and_all_geographic_files_match(self):
        raw=(ROOT/'app/src/main/assets/address-index.json').read_bytes()
        source=(ROOT/'app/src/main/java/io/github/reihlinstinct/reporter/municipal/OnlineStreetDirectory.kt').read_text()
        self.assertIn(hashlib.sha256(raw).hexdigest(),source)
        index=json.loads(raw)
        self.assertEqual(244,len(index['regions']))
        points=0
        for region in index['regions']:
            data=(ROOT/'tools/address-lookup/data/v1'/ (region['id']+'.json')).read_bytes()
            self.assertEqual(region['sha256'],hashlib.sha256(data).hexdigest())
            points+=len(json.loads(data)['addresses'])
        self.assertEqual(22627,points)
    def test_large_asset_not_packaged_and_upgrade_fixture_retained(self):
        build=(ROOT/'app/build.gradle.kts').read_text()
        self.assertIn('from("src/main/assets") { exclude("addresses.json") }',build)
        self.assertIn('getByName("main") { assets.setSrcDirs(listOf(layout.buildDirectory.dir("generated/regional-main-assets").get().asFile)) }',build)
        self.assertIn('getByName("androidTest") { assets.srcDir(layout.buildDirectory.dir("generated/address-test-assets").get().asFile) }',build)
        self.assertNotIn('getByName("androidTest") { assets.setSrcDirs',build)
    def test_no_geocoder_fallback_from_app_shell_and_import_advanced_only(self):
        shell=(ROOT/'app/src/main/java/io/github/reihlinstinct/reporter/ui/AppShell.kt').read_text()
        settings=(ROOT/'app/src/main/java/io/github/reihlinstinct/reporter/ui/SettingsScreen.kt').read_text()
        self.assertIn('addressLookup = { lat, lon ->',shell)
        self.assertIn('onlineAddresses.loadForLocation(lat, lon)',shell)
        block=settings.split('if (advanced106) {',1)[1]
        self.assertIn('addressTools?.invoke()',block)
        self.assertNotIn('onlineAddresses.refresh()',shell.split('"settings" -> SettingsScreen',1)[0])
    def test_network_is_fixed_static_ids_bounded_and_no_redirects(self):
        source=(ROOT/'app/src/main/java/io/github/reihlinstinct/reporter/municipal/OnlineStreetDirectory.kt').read_text()
        self.assertIn('followRedirects(false)',source)
        self.assertIn('retryOnConnectionFailure(false)',source)
        self.assertIn('url(BASE_URL + id + ".json")',source)
        self.assertNotIn('addQueryParameter',source)
        self.assertNotIn('Authorization',source)

if __name__=='__main__':unittest.main()
