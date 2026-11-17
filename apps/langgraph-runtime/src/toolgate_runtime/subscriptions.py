# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Reconcile authorized subscription listeners in one persistent runtime process."""
from dataclasses import dataclass
import threading
import time

from .trigger_bridge import ListenerFailure, Subscription
from .trigger_transport import TriggerListener


@dataclass
class Slot:
    subscription: Subscription
    stopped: threading.Event
    thread: threading.Thread


class Subscriptions:
    def __init__(self, control, *, maximum=64, retry_seconds=2, listen=None):
        if type(maximum) is not int or not 1 <= maximum <= 256 or not 0 < retry_seconds <= 30:
            raise ValueError('Invalid listener resource limits')
        self.control,self.maximum,self.retry_seconds=control,maximum,retry_seconds
        self.listen=listen or (lambda subscription,stop:TriggerListener(control,subscription).listen_once(stop_event=stop))
        self.slots={}

    def _run(self, subscription, stopped):
        while not stopped.is_set():
            try:
                self.listen(subscription,stopped)
            except ListenerFailure:
                pass  # No local cursor, synthetic acknowledgement or effect replay.
            except Exception:
                pass  # Exception text may include credentials/provider bodies.
            stopped.wait(self.retry_seconds)

    def reconcile(self, desired):
        desired=tuple(desired)
        if (len(desired)>self.maximum or any(not isinstance(item,Subscription) for item in desired)
                or len({item.name for item in desired})!=len(desired)
                or len({item.uid for item in desired})!=len(desired)):
            raise ValueError('Invalid authorized subscription set')
        selected=set(desired)
        for key,slot in list(self.slots.items()):
            if key not in selected:
                slot.stopped.set()
            if not slot.thread.is_alive():
                slot.thread.join()
                del self.slots[key]
        occupied={slot.subscription.name for slot in self.slots.values()}
        for subscription in desired:
            if subscription in self.slots or subscription.name in occupied or len(self.slots)>=self.maximum:
                continue  # A draining session owns its name until it actually closes.
            stopped=threading.Event()
            thread=threading.Thread(target=self._run,args=(subscription,stopped),daemon=True,
                name='workflow-subscription')
            self.slots[subscription]=Slot(subscription,stopped,thread)
            thread.start()
            occupied.add(subscription.name)
        return self.status()

    def refresh(self):
        desired=self.control.active_subscriptions(maximum=self.maximum)
        return self.reconcile(desired)

    def status(self):
        return dict(scheduled=sum(slot.thread.is_alive() and not slot.stopped.is_set() for slot in self.slots.values()),
            draining=sum(slot.thread.is_alive() and slot.stopped.is_set() for slot in self.slots.values()))

    def close(self, timeout=35):
        deadline=time.monotonic()+timeout
        for slot in self.slots.values():slot.stopped.set()
        for slot in self.slots.values():slot.thread.join(max(0,deadline-time.monotonic()))
        self.slots={key:slot for key,slot in self.slots.items() if slot.thread.is_alive()}
        return not self.slots
