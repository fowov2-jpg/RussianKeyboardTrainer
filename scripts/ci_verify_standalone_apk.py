#!/usr/bin/env python3
"""Audit the shipped APKs, not just the source manifest, for screen-access removal."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def audit(build_tools: Path, apks: Path, debug_apks: Path):
    def run(tool, *args):
        return subprocess.check_output([str(build_tools / tool), *map(str, args)], text=True)

    files = sorted(apks.glob('*.apk'))
    if len(files) != 3:
        raise ValueError('Expected three standalone ABI APKs')
    results = []
    for apk in files:
        manifest = run('aapt', 'dump', 'xmltree', apk, 'AndroidManifest.xml')
        forbidden = ('ConversationAccessibilityService', 'BIND_ACCESSIBILITY_SERVICE',
                     'android.accessibilityservice.AccessibilityService', 'android.accessibilityservice')
        if any(value in manifest for value in forbidden):
            raise ValueError(f'Accessibility component remains in {apk}')
        if not all(value in manifest for value in ('RussianKeyboardImeService',
                'android.permission.BIND_INPUT_METHOD', 'android.view.InputMethod')):
            raise ValueError(f'Keyboard service missing from {apk}')
        if re.search(r'android:debuggable[^\n]*0xffffffff', manifest):
            raise ValueError(f'Standalone distribution must not be debuggable: {apk}')
        if 'uses-permission' in run('aapt', 'dump', 'permissions', apk):
            raise ValueError(f'Unexpected requested permissions in {apk}')
        resource_dump = run('aapt', 'dump', '--values', 'resources', apk)
        flags = [line for line in resource_dump.splitlines()
                 if ':bool/screen_context_supported:' in line and 't=0x12' in line]
        if len(flags) != 1 or not re.search(r't=0x12 d=0x0+(?:\s|\))', flags[0]):
            raise ValueError(f'Screen context resource must be false: {apk}')
        certificate = run('apksigner', 'verify', '--print-certs', apk)
        digest = re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', certificate).group(1)
        original = debug_apks / apk.name.replace('-standalone.apk', '-debug.apk')
        original_certificate = run('apksigner', 'verify', '--print-certs', original)
        if digest != re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', original_certificate).group(1):
            raise ValueError('Standalone must retain the current local update signature')
        run('zipalign', '-c', '4', apk)
        results.append({'apk': apk.name, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                        'badging': run('aapt', 'dump', 'badging', apk).splitlines()[0],
                        'accessibility_service': False, 'screen_context_resource': False,
                        'requested_permissions': [], 'debuggable': False,
                        'ime_service': True, 'signature_and_alignment': 'passed',
                        'same_certificate_as_current_debug': True})
        print('Standalone component audit passed:', apk.name)
    return {'apks': results, 'play_protect_approval': 'unknown; device decision not tested',
            'scope': 'Component, resource and signature verification. Does not certify installation or model quality.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-tools', type=Path,
                        default=Path(os.environ.get('ANDROID_HOME', '')) / 'build-tools/35.0.0')
    parser.add_argument('--apks', type=Path, default=Path('android/app/build/outputs/apk/standalone'))
    parser.add_argument('--debug-apks', type=Path, default=Path('android/app/build/outputs/apk/debug'))
    parser.add_argument('--out', type=Path, default=Path('android/app/build/reports/standalone-apk.json'))
    args = parser.parse_args()
    report = audit(args.build_tools, args.apks, args.debug_apks)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
