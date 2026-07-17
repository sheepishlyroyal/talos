"""The Python standard library works out of the box — random, math, json,
collections, heapq, itertools, re, dataclasses, and every other pure-Python
stdlib module. Not available (blocked by the sandbox): pip packages, native
extensions like numpy, sockets/files (host IO), threading, subprocess, os.environ.

Run:  /talos script run random_demo     or:  talos run random_demo.py
One-liner form:  talos run 'import random;import talos;talos.log(random.randint(1,10))'
"""

import json
import math
import random
from collections import Counter

import talos

talos.log(random.randint(1, 10))

rolls = Counter(random.choice(["wheat", "carrot", "potato"]) for _ in range(20))
talos.log(json.dumps(dict(rolls)))

talos.log(f"cos(45 deg) = {math.cos(math.radians(45)):.4f}")

feet = talos.player_feet()
talos.log(f"standing at {feet.x:.1f} {feet.y:.1f} {feet.z:.1f}")
