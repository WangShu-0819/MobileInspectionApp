#!/usr/bin/env python3
with open('app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

depth = 0
in_function = False
found_opening_brace = False
function_line = 0

for i, line in enumerate(lines, start=1):
    if 'fun LiveInspectionScreen(' in line:
        in_function = True
        function_line = i
        print(f"\n>>> Line {i}: {line.rstrip()[:80]}")
        continue

    if in_function and not found_opening_brace:
        if '{' in line:
            found_opening_brace = True
            depth = 1
            print(f">>> Line {i} (opening brace): {line.rstrip()[:80]}")
            continue

    if in_function and found_opening_brace:
        opens = line.count('{')
        closes = line.count('}')

        if opens > 0 or closes > 0:
            old_depth = depth
            depth += opens
            depth -= closes
            if old_depth != depth:
                print(f">>> Line {i} (depth {old_depth} -> {depth}, {opens-opens if opens != closes else ''}{opens-closes}): {line.rstrip()[:80]}")

        if depth == 0:
            print(f"\n>>> Function ENDS at line {i}\n")
            in_function = False

print(f"\nFinal depth at end of file: {depth}")
