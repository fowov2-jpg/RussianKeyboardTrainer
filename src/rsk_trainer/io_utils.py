from __future__ import annotations
import json, random
from pathlib import Path
import numpy as np
import torch

def seed_all(seed:int):
    random.seed(seed); np.random.seed(seed); torch.manual_seed(seed)
    if torch.cuda.is_available(): torch.cuda.manual_seed_all(seed)

def load_config(path): return json.loads(Path(path).read_text(encoding="utf-8"))
def save_json(data, path): Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
