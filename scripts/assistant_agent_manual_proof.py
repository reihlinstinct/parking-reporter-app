#!/usr/bin/env python3
"""Drive only the official testing agent's deterministic Debug screen on a fresh emulator.
Never opens a returned PendingIntent, takes a photograph or submits a report.
"""
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

OUT = Path('app/build/reports/assistant-invocation')
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args):
    return subprocess.check_output(['adb', *args], text=True, timeout=30)

def nodes(stage):
    adb('shell', 'uiautomator', 'dump', '/sdcard/agent-window.xml')
    xml = adb('shell', 'cat', '/sdcard/agent-window.xml')
    (OUT / (stage + '.xml')).write_text(xml)
    with (OUT / (stage + '.png')).open('wb') as stream:
        subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=stream, check=True)
    return list(ET.fromstring(xml).iter('node'))

def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(3)

result = {'official_agent_manual': False, 'pending_intent_opened': False,
          'api_key_used': False, 'account_used': False, 'steps': []}
try:
    time.sleep(4)
    view = nodes('01-agent-launch')
    for node in view:
        if node.attrib.get('text') == 'Debug' or node.attrib.get('content-desc') == 'Debug':
            tap(node)
            break
    view = nodes('02-agent-debug')
    field = next(n for n in view if n.attrib.get('class') == 'android.widget.EditText')
    tap(field)
    adb('shell', 'input', 'text', 'il.sidewalks.reporter')
    time.sleep(3)
    view = nodes('03-agent-package-search')
    candidates = [n for n in view if n.attrib.get('text') == 'il.sidewalks.reporter' and n.attrib.get('class') != 'android.widget.EditText']
    if not candidates:
        raise RuntimeError('Official agent did not list the reporter package in manual discovery')
    tap(candidates[0])
    view = nodes('04-agent-functions')
    function = next((n for n in view if 'startNewReport' in n.attrib.get('text', '')), None)
    if function is None:
        raise RuntimeError('Reporter selected but official agent did not discover startNewReport')
    tap(function)
    view = nodes('05-agent-function-expanded')
    button = next(n for n in view if n.attrib.get('text') == 'Run')
    tap(button)
    view = nodes('06-agent-execution-result')
    texts = [n.attrib.get('text', '') for n in view]
    result['result_text'] = texts
    # The app intentionally returns an immutable action, not a filed report.
    result['official_agent_manual'] = any(t == 'Action Required' for t in texts)
    if not result['official_agent_manual']:
        raise RuntimeError('Official agent did not display the expected returned action')
except Exception as error:
    result['blocker'] = str(error)
    try:
        nodes('07-agent-final-blocker')
    except Exception:
        pass
finally:
    (OUT / 'manual-agent-result.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))
    time.sleep(5)
