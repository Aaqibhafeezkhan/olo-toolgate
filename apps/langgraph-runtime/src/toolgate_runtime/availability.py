# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Ephemeral diagnostics only; never execution authority or credential storage."""
import json
import os
from pathlib import Path
import random
import threading
import time


class Availability:
    def __init__(self,path=None):
        self.path=Path(path) if path else None
        self.lock=threading.Lock()

    def set(self,state):
        if state not in ('CONNECTING','READY','AUTH_REQUIRED','BACKOFF','STOPPED'):
            raise ValueError('Invalid runtime availability')
        with self.lock:
            if self.path:
                temporary=self.path.with_suffix('.tmp')
                with open(temporary,'w',encoding='ascii',opener=lambda p,f:os.open(p,f,0o600)) as stream:
                    json.dump(dict(state=state,updatedAt=time.monotonic()),stream)
                os.replace(temporary,self.path)


def read_status(path):
    try:
        value=json.loads(Path(path).read_text(encoding='ascii'))
        age=time.monotonic()-value['updatedAt']
        if value['state'] in ('CONNECTING','READY','AUTH_REQUIRED','BACKOFF','STOPPED') and age>=0 and (value['state']!='READY' or age<=15):
            return value['state']
    except (OSError,ValueError,KeyError,TypeError):pass
    return 'CONNECTING'


class Backoff:
    def __init__(self):self.attempt=0
    def reset(self):self.attempt=0
    def next(self):
        ceiling=min(30,2**min(self.attempt,5));self.attempt+=1
        return random.uniform(ceiling*.5,ceiling)
