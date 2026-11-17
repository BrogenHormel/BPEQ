#!/usr/bin/env bash
# Emulator UI helper for smoke tests (development only; the phone itself is never connected).
#   ui.sh tap "Text"      tap the first node whose text/content-desc contains Text
#   ui.sh dump            print visible texts
#   ui.sh shot file.png   screenshot
ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
dump() { $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $ADB shell cat /sdcard/ui.xml; }
case "$1" in
  tap)
    xy=$(dump | python3 -I -c "
import sys,re,xml.etree.ElementTree as ET
t=sys.argv[1]; nodes=list(ET.fromstring(sys.stdin.read()).iter('node'))
lab=lambda n:(n.get('text') or '', n.get('content-desc') or '')
m=[n for n in nodes if t in lab(n)] or [n for n in nodes if any(t in x for x in lab(n))]
if m:
  a,b,c,d=map(int,re.findall(r'\d+',m[0].get('bounds'))); print((a+c)//2,(b+d)//2)
" "$2")
    [ -n "$xy" ] || { echo "not found: $2"; exit 1; }
    $ADB shell input tap $xy ;;
  dump) dump | python3 -I -c "
import sys,xml.etree.ElementTree as ET
for n in ET.fromstring(sys.stdin.read()).iter('node'):
  s=(n.get('text') or n.get('content-desc') or '').strip()
  if s: print(s)
" ;;
  shot) $ADB exec-out screencap -p > "$2" ;;
esac
