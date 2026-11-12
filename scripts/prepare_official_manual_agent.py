#!/usr/bin/env python3
"""Remove unavailable upstream screenshot-test-only tooling for standardDebug assembly.
No agent runtime/source behavior is patched. Record the exact diff in CI logs.
"""
from pathlib import Path
import subprocess
root = Path('official-appfunctions/agent')
p = root / 'app/build.gradle.kts'
s = p.read_text()
for text in [
    '    alias(libs.plugins.screenshot)\n',
    '    @Suppress("UnstableApiUsage")\n    experimentalProperties["android.experimental.enableScreenshotTest"] = true\n',
    '        screenshotTests {\n            imageDifferenceThreshold = 0.01f // 1%\n        }\n',
    '    screenshotTestImplementation(libs.androidx.compose.ui.tooling)\n',
    '    screenshotTestImplementation(libs.screenshot.validation.api)\n',
]:
    assert s.count(text) == 1, text
    s = s.replace(text, '')
p.write_text(s)
subprocess.run(['git', '-C', str(root), 'diff', '--', 'app/build.gradle.kts'], check=True)
