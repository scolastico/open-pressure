#!/usr/bin/env python3
"""Exercise release preparation in disposable repositories without touching this checkout."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT=Path(__file__).with_name('prepare-release.py').resolve()
GRADLE="android {\n    defaultConfig {\n        versionCode 16\n        versionName '0.3.0'\n    }\n}\n"

class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name)
        self.git('init','--initial-branch=main')
        self.file=self.root/'app/build.gradle'
        self.file.parent.mkdir()
        self.file.write_text(GRADLE)
        self.commit_fixture('Initial source')

    def git(self,*args,input=None):
        return subprocess.check_output(['git',*args],cwd=self.root,input=input,text=True,stderr=subprocess.PIPE).strip()

    def commit_fixture(self,message):
        # Raw synthetic objects avoid depending on a developer's commit-signing setup.
        self.git('add','app/build.gradle')
        tree=self.git('write-tree')
        parents=[]
        if (self.root/'.git/refs/heads/main').exists(): parents=['parent '+self.git('rev-parse','HEAD')]
        content='\n'.join(['tree '+tree,*parents,'author Fixture <fixture@example.invalid> 1 +0000','committer Fixture <fixture@example.invalid> 1 +0000','',message,''])
        commit=self.git('hash-object','-t','commit','-w','--stdin',input=content)
        self.git('update-ref','HEAD',commit)

    def prepare(self,version,success=True):
        result=subprocess.run(['python3',str(SCRIPT),version],cwd=self.root,text=True,capture_output=True)
        if success:
            self.assertEqual(0,result.returncode,result.stderr)
            return json.loads(result.stdout)
        self.assertNotEqual(0,result.returncode,result.stdout)
        return result

    def test_counts_the_upcoming_release_commit(self):
        self.commit_fixture('Another change')
        result=self.prepare('1.2.3')
        self.assertEqual({'version':'1.2.3','revision':3,'tag':'v1.2.3','prerelease':False,'changed':True},result)
        self.assertIn('versionCode 3',self.file.read_text())
        self.assertIn("versionName '1.2.3'",self.file.read_text())
        self.assertEqual(GRADLE.replace('versionCode 16','versionCode 3').replace("versionName '0.3.0'","versionName '1.2.3'"),self.file.read_text())
        self.commit_fixture('chore(release): v1.2.3')
        self.assertEqual('3',self.git('rev-list','--count','HEAD'))

    def test_rerun_reuses_the_release_commit(self):
        self.prepare('1.0.0')
        self.commit_fixture('chore(release): v1.0.0')
        result=self.prepare('1.0.0')
        self.assertFalse(result['changed'])
        self.assertEqual(2,result['revision'])
        self.assertEqual('',self.git('status','--porcelain'))

    def test_prerelease_and_build_metadata(self):
        result=self.prepare('1.2.3-rc.1+build.4')
        self.assertTrue(result['prerelease'])
        self.assertEqual('v1.2.3-rc.1+build.4',result['tag'])

    def test_hyphen_in_build_metadata_is_not_prerelease(self):
        self.assertFalse(self.prepare('1.2.3+build-test')['prerelease'])

    def test_invalid_versions_never_modify_files(self):
        for version in ['v1.2.3','01.2.3','1.2','1.2.3-01','1.2.3+','1.2.3\n','1.2.3;touch /tmp/pwn','١.2.3']:
            with self.subTest(version=version): self.prepare(version,False)
            self.assertEqual(GRADLE,self.file.read_text())

    def test_dirty_checkout_rejected(self):
        (self.root/'uncommitted.txt').write_text('work')
        self.prepare('1.2.3',False)
        self.assertEqual(GRADLE,self.file.read_text())

    def test_shallow_history_rejected(self):
        (self.root/'.git/shallow').write_text(self.git('rev-parse','HEAD')+'\n')
        self.prepare('1.2.3',False)
        self.assertEqual(GRADLE,self.file.read_text())

    def test_ambiguous_version_declaration_is_not_partially_written(self):
        content=GRADLE+"\nversionName '0.0.1'\n"
        self.file.write_text(content)
        self.commit_fixture('Ambiguous build file')
        self.prepare('1.2.3',False)
        self.assertEqual(content,self.file.read_text())

if __name__=='__main__': unittest.main()
