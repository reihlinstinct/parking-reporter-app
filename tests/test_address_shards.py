import importlib.util
import hashlib
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('shards',ROOT/'tools/address-lookup/build_address_shards.py')
target=importlib.util.module_from_spec(spec);spec.loader.exec_module(target)


def polygon(rid,west,east):
    return dict(type='Feature',properties=dict(id=rid),geometry=dict(type='Polygon',coordinates=[
        [[west,31.0],[east,31.0],[east,32.0],[west,32.0],[west,31.0]]]))


class ShardBuilderTest(unittest.TestCase):
    def dataset(self):
        return dict(version=1,streets=[dict(StreetCode='1',StreetName='synthetic street',aliases=['alias'])],
                    addresses=[[31.5,34.5,'1','synthetic street'],[31.5,35.5,'2','alias']])
    def boundaries(self):
        return dict(type='FeatureCollection',features=[polygon('west',34,35),polygon('east',35,36)])
    def test_street_crossing_regions_has_complete_dependency_index(self):
        files=target.build(self.dataset(),self.boundaries())
        index=json.loads(files['index.json'])
        self.assertEqual([r['streetCodes'] for r in index['regions']],[['1'],['1']])
        for row in index['regions']:
            self.assertEqual(row['sha256'],hashlib.sha256(files[row['id']+'.json']).hexdigest())
        self.assertEqual(len(json.loads(files['west.json'])['addresses']),1)
        self.assertEqual(files,target.build(self.dataset(),self.boundaries()))
    def test_uncovered_point_refuses_instead_of_silent_data_loss(self):
        data=self.dataset();data['addresses'].append([30,33,'3','synthetic street'])
        with self.assertRaisesRegex(ValueError,'coverage'):target.build(data,self.boundaries())
    def test_shared_polygon_boundary_preserves_both_memberships(self):
        data=self.dataset();data['addresses']=[[31.5,35,'1','synthetic street']]
        files=target.build(data,self.boundaries())
        for rid in ('west','east'):self.assertEqual(len(json.loads(files[rid+'.json'])['addresses']),1)
    def test_unique_short_name_tail_dependencies_are_retained(self):
        data=self.dataset();data['streets'][0]['StreetName']='general synthetic street'
        files=target.build(data,self.boundaries());index=json.loads(files['index.json'])
        self.assertTrue(all('1' in r['streetCodes'] for r in index['regions']))
    def test_unsafe_ids_and_duplicate_regions_refuse(self):
        for features in ([polygon('../outside',34,36)], [polygon('same',34,35),polygon('same',35,36)]):
            with self.assertRaises(ValueError):target.build(self.dataset(),dict(features=features))
    def test_hole_excludes_interior_but_not_its_boundary(self):
        polygon=[[[34,31],[36,31],[36,32],[34,32],[34,31]],
                 [[34.5,31.25],[35.5,31.25],[35.5,31.75],[34.5,31.75],[34.5,31.25]]]
        self.assertFalse(target.contains([polygon],[35,31.5]))
        self.assertTrue(target.contains([polygon],[35,31.25]))


if __name__=='__main__':unittest.main()
