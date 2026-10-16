#!/usr/bin/env python3
"""Prepare versionName/versionCode for one release commit; print workflow metadata as JSON."""
import json
from pathlib import Path
import re
import subprocess
import sys

SEMVER=re.compile(r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-(?P<pre>[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?')
NAME=re.compile(r"^(\s*versionName\s+)'([^']*)'\s*$",re.MULTILINE)
CODE=re.compile(r'^(\s*versionCode\s+)([0-9]+)\s*$',re.MULTILINE)

def git(*args):
    return subprocess.check_output(['git',*args],text=True,stderr=subprocess.PIPE).strip()

def prepare(version):
    match=SEMVER.fullmatch(version)
    if not match or any(part.isdigit() and len(part)>1 and part[0]=='0' for part in (match.group('pre') or '').split('.')):
        raise ValueError('Enter a SemVer 2.0 version without a v prefix, for example 0.4.0 or 0.4.0-rc.1')
    if git('rev-parse','--is-shallow-repository')!='false':
        raise ValueError('Full Git history is required to calculate versionCode')
    if git('status','--porcelain'):
        raise ValueError('Release preparation requires a clean checkout')
    count=int(git('rev-list','--count','HEAD'))
    path=Path('app/build.gradle')
    original=path.read_text()
    names=list(NAME.finditer(original)); codes=list(CODE.finditer(original))
    if len(names)!=1 or len(codes)!=1:
        raise ValueError('Expected exactly one versionName and versionCode in app/build.gradle')
    tag='v'+version
    # A failed build can be rerun after the release commit was already pushed.
    reuse=names[0][2]==version and int(codes[0][2])==count and git('log','-1','--format=%s')=='chore(release): '+tag
    revision=count if reuse else count+1
    if not 1<=revision<=2100000000:
        raise ValueError('Commit count is outside Android versionCode limits')
    if not reuse:
        updated=NAME.sub(lambda m:m[1]+"'"+version+"'",original)
        updated=CODE.sub(lambda m:m[1]+str(revision),updated)
        path.write_text(updated)
    return {'version':version,'revision':revision,'tag':tag,'prerelease':match.group('pre') is not None,'changed':not reuse}

if __name__=='__main__':
    try:
        if len(sys.argv)!=2: raise ValueError('Usage: python3 tools/prepare-release.py VERSION')
        print(json.dumps(prepare(sys.argv[1])))
    except (ValueError,OSError,subprocess.CalledProcessError) as error:
        print('Release preparation failed: '+str(error),file=sys.stderr)
        sys.exit(1)
