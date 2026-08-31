#!/usr/bin/env python3
with open('app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

depth = 0
for i, line in enumerate(lines, start=1):
    if i < 103:
        continue
    if i > 761:
        break

    opens = line.count('{')
    closes = line.count('}')

    if opens > 0 or closes > 0:
        depth_before = depth
        depth += opens
        depth -= closes
        if depth_before != depth:
            print(f"{i:3d}: depth {depth_before:2d} -> {depth:2d} ({'+' if opens > closes else ''}{opens-closes}): {line.rstrip()[:80]}")

print(f"\nFinal depth: {depth}")
