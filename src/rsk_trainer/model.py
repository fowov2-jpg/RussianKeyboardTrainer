from __future__ import annotations

import torch
from torch import nn

class ContextRanker(nn.Module):
    def __init__(self, vocab_size: int, max_chars: int, d_model: int = 128, nhead: int = 4,
                 num_layers: int = 3, dim_feedforward: int = 384, dropout: float = 0.1):
        super().__init__()
        self.token = nn.Embedding(vocab_size, d_model, padding_idx=0)
        self.pos = nn.Embedding(max_chars, d_model)
        layer = nn.TransformerEncoderLayer(
            d_model=d_model, nhead=nhead, dim_feedforward=dim_feedforward,
            dropout=dropout, batch_first=True, activation="gelu", norm_first=True
        )
        self.encoder = nn.TransformerEncoder(layer, num_layers=num_layers, enable_nested_tensor=False)
        self.norm = nn.LayerNorm(d_model)
        self.head = nn.Sequential(nn.Linear(d_model, d_model // 2), nn.GELU(), nn.Dropout(dropout), nn.Linear(d_model // 2, 1))

    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        b, n = input_ids.shape
        positions = torch.arange(n, device=input_ids.device).unsqueeze(0).expand(b, n)
        x = self.token(input_ids) + self.pos(positions)
        x = self.encoder(x, src_key_padding_mask=~attention_mask.bool())
        cls = self.norm(x[:, 0])
        return self.head(cls).squeeze(-1)
