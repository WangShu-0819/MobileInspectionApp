#!/usr/bin/env python3
with open('app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

depth = 0
in_function = False
function_start = 0
found_opening_brace = False

for i, line in enumerate(lines, start=1):
    if 'fun LiveInspectionScreen(' in line:
        in_function = True
        function_start = i
        found_opening_brace = False
        print(f"\n>>> LiveInspectionScreen starts at line {i}")
        continue

    if in_function and not found_opening_brace:
        if '{' in line:
            found_opening_brace = True
            depth = 1
            print(f">>> Opening brace at line {i}: {line.rstrip()[:80]}")
            continue
        else:
            continue

    if in_function and found_opening_brace:
        opens = line.count('{')
        closes = line.count('}')

        old_depth = depth
        depth += opens
        depth -= closes

        if depth == 0:
            print(f">>> LiveInspectionScreen ENDS at line {i} (depth {old_depth} -> {depth}): {line.rstrip()[:80]}")
            in_function = False
            found_opening_brace = False

print(f"\nFinal depth: {depth}")
