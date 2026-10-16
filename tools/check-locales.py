#!/usr/bin/env python3
"""Validate shipped locale coverage, format arguments, plurals and invariant product branding.

Plural categories in locale-plurals.json are the union of Android lint's API 26
and API 36 CLDR datasets. The checker has no third-party Python dependencies.
"""
import argparse
import collections
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

FORMAT=re.compile(r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]')
NAMESPACE='{http://schemas.android.com/apk/res/android}'
FOLDERS={'he':'values-iw','id':'values-in','fil':'values-b+fil','zh-Hans':'values-b+zh+Hans','zh-Hant':'values-b+zh+Hant'}
BRANDS=('OpenPressure','Health Connect')

def catalogue(paths):
    result={}
    for path in paths:
        for node in ET.parse(path).getroot():
            if node.tag not in ('string','plurals'): continue
            name=node.get('name')
            if name in result: raise ValueError(f'Duplicate resource {name} in {path}')
            values={item.get('quantity'):''.join(item.itertext()) for item in node} if node.tag=='plurals' else {None:''.join(node.itertext())}
            result[name]=(node.tag,node.get('translatable')!='false',values)
    return result

def check_catalogue(base,localized,quantities,label):
    errors=[]
    required={key for key,value in base.items() if value[1]}
    for missing in sorted(required-localized.keys()): errors.append(f'{label}: missing {missing}')
    for key,(tag,translatable,values) in localized.items():
        if key not in base: errors.append(f'{label}: unknown resource {key}'); continue
        base_tag,base_translatable,base_values=base[key]
        if not base_translatable: errors.append(f'{label}: overrides invariant resource {key}'); continue
        if tag!=base_tag: errors.append(f'{label}: resource type changed for {key}'); continue
        if tag=='plurals':
            for quantity in set(quantities)-values.keys(): errors.append(f'{label}: {key} missing plural {quantity}')
        for quantity,value in values.items():
            original=base_values.get(quantity,base_values.get('other',''))
            if not value.strip(): errors.append(f'{label}: empty {key}')
            if collections.Counter(FORMAT.findall(original))!=collections.Counter(FORMAT.findall(value)):
                errors.append(f'{label}: format arguments differ in {key}.{quantity}')
            for brand in BRANDS:
                if original.count(brand)!=value.count(brand): errors.append(f'{label}: product name changed in {key}.{quantity}: {brand}')
            if re.search(r'(?<!\\)[\'\"]',value): errors.append(f'{label}: unescaped Android quote in {key}.{quantity}')
    return errors

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources',type=Path,default=Path(__file__).resolve().parents[1]/'app/src/main/res')
    args=parser.parse_args()
    res=args.resources
    base=catalogue(sorted((res/'values').glob('strings*.xml')))
    rules=json.loads((Path(__file__).with_name('locale-plurals.json')).read_text())
    locales=[n.get(NAMESPACE+'name') for n in ET.parse(res/'xml/locales_config.xml').getroot()]
    errors=[]
    if base.get('main_app_name',('x',True,{}))!=('string',False,{None:'OpenPressure'}): errors.append('App name must be the non-translatable OpenPressure resource')
    for locale in locales:
        if locale=='en': continue
        folder=res/FOLDERS.get(locale,'values-'+locale)
        files=sorted(folder.glob('strings*.xml'))
        if not files: errors.append(f'{locale}: no localized strings'); continue
        try: errors.extend(check_catalogue(base,catalogue(files),rules[locale],locale))
        except (ValueError,ET.ParseError) as error: errors.append(str(error))
    if errors:
        for error in errors: print(error,file=sys.stderr)
        return 1
    print(f'Locales: {len(locales)} language/region options; complete resource coverage, plural categories, format arguments and invariant brands passed')
    return 0

if __name__=='__main__': sys.exit(main())
