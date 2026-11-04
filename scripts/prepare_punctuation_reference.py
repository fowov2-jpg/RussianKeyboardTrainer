#!/usr/bin/env python3
"""Reproduce the small test-only fixture using pinned real model assets and HF WordPiece.

Requires numpy, tokenizers and onnxruntime (reference generated with 1.20.1).
No downloads, user data or training. Do not package this fixture in the APK.
"""
import re
import struct
from pathlib import Path
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'android/app/src/main/assets/punctuation'
CASES = [
    ('привет как дела', ''), ('как дела у тебя', ''), ('я знаю как дела', ''),
    ('я думаю что завтра будет дождь', ''), ('привет', ''), ('Привет, как дела?', ''),
    ('я работаю в компании', ''), ('спасибо за помощь', ''),
    ('я думаю что документы готовы', 'Анна Можешь прислать документы'),
    ('https://example.com/привет', ''), ('@привет как дела', ''), ('ПрЕвЕт как дела', ''),
    # New transfer controls, separate from the 18 observed corpus differences.
    ('Он инженер.', ''), ('Мы соседи.', ''), ('Нужно это проверить.', ''),
    ('Спасибо, что позвонили.', ''), ('Я думаю, они коллеги.', ''), ('Хочу быть как ты.', '')]


def main():
    tokenizer = Tokenizer.from_file(str(ASSETS / 'tokenizer.json'))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    session = ort.InferenceSession(str(ASSETS / 'model.bin'), options, providers=['CPUExecutionProvider'])
    output = bytearray(b'RUPTEST1')
    def integer(value):
        output.extend(struct.pack('>i', value))
    def string(value):
        value = value.encode('utf-8'); integer(len(value)); output.extend(value)
    integer(len(CASES))
    for text, history in CASES:
        pieces = [tokenizer.encode(word.lower(), add_special_tokens=False).ids for word in re.findall(r'[^\W_]+', text)]
        context = [token for word in re.findall(r'[^\W_]+', history)[-24:]
                   for token in tokenizer.encode(word.lower(), add_special_tokens=False).ids]
        budget = max(0, min(40, 192 - sum(map(len, pieces)) - 3))
        context = context[-budget:] if budget else []
        ids = [2] + (context + [3] if context else [])
        positions = []
        for word in pieces:
            positions.append(len(ids)); ids.extend(word)
        ids.append(3)
        values = np.array([ids], dtype=np.int64)
        logits = session.run(None, {'input_ids': values, 'attention_mask': np.ones_like(values),
                                    'token_type_ids': np.zeros_like(values)})[0][0]
        string(text); string(history); integer(len(ids))
        for token in ids:
            integer(token)
        integer(len(positions))
        for position in positions:
            integer(position)
            output.extend(struct.pack('>33f', *logits[position]))
    path = ROOT / 'android/app/src/test/resources/punctuation_reference.bin'
    path.write_bytes(output)
    print(f'Wrote {len(CASES)} real-model reference cases, {len(output)} bytes')


if __name__ == '__main__':
    main()
