#!/usr/bin/env python3
"""Fail closed on the built preview APK's aapt shortcut resource tree."""
import re
import sys

PACKAGE = 'il.sidewalks.reporter.preview'
ACTIVITY = 'il.sidewalks.reporter.MainActivity'

def verify(tree):
    def values(attribute):
        lines = [line for line in tree.splitlines() if 'A: android:' + attribute in line]
        result = []
        for line in lines:
            match = re.search(r'A: android:' + attribute + r'\([^)]*\)="([^"]+)"', line)
            if not match:
                raise ValueError('Unrecognized compiled shortcut attribute')
            result.append(match.group(1))
        return result
    if values('targetPackage') != [PACKAGE, PACKAGE]:
        raise ValueError('Both compiled shortcut intents must target the preview package')
    if values('targetClass') != [ACTIVITY, ACTIVITY]:
        raise ValueError('Both compiled shortcut intents must target the installed activity class')

if __name__ == '__main__':
    verify(sys.stdin.read())
    print('Built preview shortcut package/class targets verified (2 intents).')
