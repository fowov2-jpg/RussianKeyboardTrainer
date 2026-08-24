from __future__ import annotations

def damerau_levenshtein(a: str, b: str) -> int:
    if a == b: return 0
    if not a: return len(b)
    if not b: return len(a)
    prev2 = None
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cost = 0 if ca == cb else 1
            v = min(cur[j-1] + 1, prev[j] + 1, prev[j-1] + cost)
            if prev2 is not None and i > 1 and j > 1 and ca == b[j-2] and a[i-2] == cb:
                v = min(v, prev2[j-2] + 1)
            cur.append(v)
        prev2, prev = prev, cur
    return prev[-1]
