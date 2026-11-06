# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Qualification must bind I/O limits to the container store's whole block device."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from tools.security import block_device


@unittest.skipUnless(sys.platform=="linux","Linux block-device qualification only")
class BlockDeviceTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name)
        self.sysfs=self.root/'sys'
        (self.sysfs/'dev/block').mkdir(parents=True)
        self.storage=self.root/'actual-container-store'
        self.storage.mkdir()

    def disk(self,name,number):
        node=self.sysfs/'devices/block'/name
        (node/'queue').mkdir(parents=True)
        (node/'dev').write_text(number+'\n')
        (self.sysfs/'dev/block'/number).symlink_to(node)
        return node

    def partition(self,disk,name,number):
        node=disk/name
        node.mkdir()
        (node/'dev').write_text(number+'\n')
        (node/'partition').write_text('1\n')
        (self.sysfs/'dev/block'/number).symlink_to(node)

    def resolve(self,number,explicit=None):
        original=os.stat
        def stat(path,*args,**kwargs):
            if Path(path)==self.storage:
                major,minor=map(int,number.split(':'))
                return SimpleNamespace(st_dev=os.makedev(major,minor))
            return original(path,*args,**kwargs)
        with patch.object(block_device.os,'stat',side_effect=stat):
            return block_device.io_device_for_storage(self.storage,explicit,sysfs=self.sysfs)

    def test_whole_device_is_preserved(self):
        self.disk('sda','8:0')
        self.assertEqual('8:0',self.resolve('8:0'))

    def test_partition_maps_to_whole_disk_without_assuming_minor_number(self):
        sata=self.disk('sda','8:0')
        self.partition(sata,'sda1','8:1')
        nvme=self.disk('nvme0n1','259:7')
        self.partition(nvme,'nvme0n1p2','259:12')
        self.assertEqual('8:0',self.resolve('8:1'))
        self.assertEqual('259:7',self.resolve('259:12'))

    def test_explicit_whole_device_supports_virtual_filesystem(self):
        self.disk('sdb','8:16')
        self.assertEqual('8:16',self.resolve('0:42','8:16'))

    def test_explicit_partition_is_rejected_with_correct_device(self):
        disk=self.disk('sda','8:0')
        self.partition(disk,'sda1','8:1')
        with self.assertRaisesRegex(ValueError,'whole block device 8:0'):
            self.resolve('8:1','8:1')

    def test_missing_virtual_and_nonqueued_devices_fail_closed(self):
        node=self.disk('sda','8:0')
        (node/'queue').rmdir()
        for number in ('0:42','8:99','8:0'):
            with self.subTest(number=number),self.assertRaises(ValueError):
                self.resolve(number)

    def test_mismatched_topology_cannot_select_another_device(self):
        node=self.disk('sda','8:0')
        (node/'dev').write_text('8:16\n')
        with self.assertRaisesRegex(ValueError,'does not match'):
            self.resolve('8:0')

    def test_missing_partition_parent_is_not_guessed(self):
        disk=self.disk('sda','8:0')
        self.partition(disk,'sda1','8:1')
        (disk/'dev').unlink()
        with self.assertRaises(ValueError):
            self.resolve('8:1')

    def test_storage_inspection_uses_service_account_and_actual_graph_root(self):
        account=SimpleNamespace(pw_uid=1000,pw_name='fixture',pw_dir=str(self.root/'home'))
        info={'host':{'security':{'rootless':True},'serviceIsRemote':False},
              'store':{'graphRoot':str(self.storage)}}
        result=subprocess.CompletedProcess([],0,json.dumps(info),'')
        with patch.object(block_device.os,'geteuid',return_value=0),patch.object(
                block_device.subprocess,'run',return_value=result) as run:
            self.assertEqual(self.storage,block_device.container_storage_path(account))
        command=run.call_args.args[0]
        self.assertEqual(['/usr/sbin/runuser','-u','fixture','--','/usr/bin/env','-i'],command[:6])
        self.assertIn('/usr/bin/podman',command)
        self.assertEqual({'HOME','XDG_RUNTIME_DIR','PATH'},set(run.call_args.kwargs['env']))

    def test_remote_rootful_relative_or_malformed_storage_is_rejected(self):
        account=SimpleNamespace(pw_uid=1000,pw_name='fixture',pw_dir=str(self.root/'home'))
        valid={'host':{'security':{'rootless':True},'serviceIsRemote':False},
               'store':{'graphRoot':str(self.storage)}}
        for info in ([],{},valid|{'host':{'security':{'rootless':False},'serviceIsRemote':False}},
                     valid|{'host':{'security':{'rootless':True},'serviceIsRemote':True}},
                     valid|{'store':{'graphRoot':'relative'}},valid|{'store':{'graphRoot':str(self.root/'absent')}}):
            with self.subTest(info=info),patch.object(block_device.os,'geteuid',return_value=1000),patch.object(
                    block_device.subprocess,'run',return_value=subprocess.CompletedProcess([],0,json.dumps(info),'')):
                with self.assertRaises(ValueError):
                    block_device.container_storage_path(account)
