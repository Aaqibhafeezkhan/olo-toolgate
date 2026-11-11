#!/usr/bin/env python3
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Run the HotFolder trigger listener or chat with an Ollama model."""
import argparse
import json
import os
from pathlib import Path
import sys
from urllib.error import URLError
from urllib.request import Request, urlopen


def ollama_request(path, body=None):
    origin = os.environ.get('OLLAMA_HOST', 'http://127.0.0.1:11434').rstrip('/')
    if '://' not in origin:
        origin = 'http://' + origin
    data = json.dumps(body).encode() if body is not None else None
    request = Request(origin + path, data=data, headers={'Content-Type': 'application/json'})
    with urlopen(request, timeout=90 if data is not None else 5) as response:
        return json.load(response)


def chat():
    models = [item['name'] for item in ollama_request('/api/tags')['models']]
    if not models:
        print('Ollama has no installed models.')
        return
    for index, name in enumerate(models, 1):
        print(f'{index}. {name}')
    selection = input('Model number (Enter to return): ').strip()
    if not selection:
        return
    if not selection.isdecimal() or not 1 <= int(selection) <= len(models):
        print('Choose a model number from the list.')
        return
    model = models[int(selection) - 1]
    messages = []
    print(f'Chatting with {model}. Type /back to return.')
    while True:
        prompt = input('You: ').strip()
        if prompt == '/back':
            return
        if not prompt:
            continue
        pending = messages + [{'role': 'user', 'content': prompt}]
        result = ollama_request('/api/chat', {'model': model, 'messages': pending, 'stream': False})
        reply = result['message']['content']
        print(f'{model}: {reply}')
        messages = pending + [{'role': 'assistant', 'content': reply}]


def run_hotfolder(command):
    bringup = Path(__file__).resolve().parents[1] / 'bringup.py'
    os.execv(sys.executable, [sys.executable, str(bringup), command])


def console():
    while True:
        print('\n1. Register for HotFolder events and listen\n2. Chat with an Ollama model\n0. Exit')
        choice = input('Choose an option: ').strip()
        if choice == '0':
            return
        if choice == '1':
            run_hotfolder('listen')
        elif choice == '2':
            try:
                chat()
            except (URLError, TimeoutError, ValueError, KeyError) as error:
                print(f'Ollama chat failed: {error}')
        else:
            print('Choose 1, 2, or 0.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', nargs='?', choices=('listen', 'verify', 'console'), default='listen')
    command = parser.parse_args().command
    if command == 'console':
        try:
            console()
        except (EOFError, KeyboardInterrupt):
            print()
    else:
        run_hotfolder(command)
